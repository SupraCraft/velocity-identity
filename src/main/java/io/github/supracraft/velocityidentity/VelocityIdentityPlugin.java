package io.github.supracraft.velocityidentity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreTransferEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.GameProfile;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class VelocityIdentityPlugin {
    private static final Component UNAVAILABLE =
            Component.text("This admission method is not currently available.");
    private static final Component SESSION_MISSING =
            Component.text("Identity admission state is unavailable; reconnect.");
    private static final Component AUTHORITY_NOT_READY =
            Component.text("Identity authority is not ready; reconnect later.");
    private static final Component IDENTITY_CONFLICT =
            Component.text("Another plugin changed the identity login mode; connection denied.");
    private static final Component PROFILE_CONFLICT =
            Component.text("The resolved Minecraft profile changed unexpectedly; connection denied.");
    private static final Component ROUTE_VIOLATION =
            Component.text("Identity policy denied the resulting server connection.");

    private static final long PENDING_MAX_AGE_NANOS = 5L * 60L * 1_000_000_000L;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final PolicyRuntime runtime = new PolicyRuntime(AdmissionPolicy.microsoftOnly());
    private final AuthorityGate authorityGate = new AuthorityGate();
    private final AtomicLong generation = new AtomicLong();

    private final Map<InboundConnection, PendingAdmission> pendingByConnection =
            new ConcurrentHashMap<>();
    private final Map<PendingLoginKey, PendingAdmission> pendingByLogin =
            new ConcurrentHashMap<>();
    private final Map<Player, ActiveSession> sessions =
            new ConcurrentHashMap<>();

    @Inject
    public VelocityIdentityPlugin(
            ProxyServer server,
            Logger logger,
            @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent ignored) {
        try {
            Files.createDirectories(dataDirectory);
            Path configPath = dataDirectory.resolve("velocity-identity.properties");
            VelocityIdentityConfig desired = VelocityIdentityConfig.load(configPath);

            EnvironmentObservation observation = VelocityObserver.observe(server);
            PolicyPlan plan = PolicyPlanner.plan(observation, runtime.current(), desired.desiredPolicy());

            RuntimeEvidenceWriter evidence = new RuntimeEvidenceWriter(dataDirectory);
            evidence.writeObservation(observation);
            evidence.writePlan(plan);

            EnvironmentObservation preApplyObservation = VelocityObserver.observe(server);
            ApplyReceipt receipt = runtime.apply(plan, preApplyObservation);
            evidence.writeApply(receipt);

            EnvironmentObservation verificationObservation = VelocityObserver.observe(server);
            VerificationReport verification =
                    PolicyVerifier.verify(plan, verificationObservation, runtime.current());
            evidence.writeVerification(verification);

            boolean verified = (receipt.status() == ApplyReceipt.ApplyStatus.APPLIED
                    || receipt.status() == ApplyReceipt.ApplyStatus.NOOP)
                    && verification.status() == VerificationReport.VerificationStatus.PASS;

            if (verified) {
                authorityGate.markVerified();
            } else {
                authorityGate.markFailure();
            }

            logger.info(
                    "VelocityIdentity reconciliation readiness={} apply={} verify={} plan={}",
                    authorityGate.readiness(),
                    receipt.status(),
                    verification.status(),
                    plan.planFingerprint());

            if (!verified) {
                logger.warn(
                        "VelocityIdentity has no newly verified policy; readiness={}.",
                        authorityGate.readiness());
            }
        } catch (Exception error) {
            authorityGate.markFailure();
            logger.error(
                    "VelocityIdentity initialization failed; new connections remain fail-closed. readiness={}",
                    authorityGate.readiness(),
                    error);
        }
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }
        if (!authorityGate.acceptsLogins()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(AUTHORITY_NOT_READY));
            return;
        }

        cleanupPending();
        AdmissionProfile profile = runtime.current().select(virtualHost(event.getConnection()));
        long connectionGeneration = generation.incrementAndGet();

        switch (profile.admissionClass()) {
            case MICROSOFT -> {
                if (event.getResult().isForceOfflineMode()) {
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(IDENTITY_CONFLICT));
                    return;
                }
                pendingByConnection.put(
                        event.getConnection(),
                        new PendingAdmission(profile, null, null, connectionGeneration, System.nanoTime()));
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
            }
            case GUEST -> {
                if (event.getResult().isOnlineModeAllowed()) {
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(IDENTITY_CONFLICT));
                    return;
                }
                GameIdentity identity = allocateGuestIdentity();
                GameProfile expectedProfile =
                        new GameProfile(identity.gameUuid(), identity.gameName(), List.of());
                pendingByConnection.put(
                        event.getConnection(),
                        new PendingAdmission(
                                profile,
                                identity,
                                expectedProfile,
                                connectionGeneration,
                                System.nanoTime()));
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
            }
            case FEDERATED, WORKLOAD ->
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(UNAVAILABLE));
        }
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        PendingAdmission pending = pendingByConnection.remove(event.getConnection());
        if (pending == null) {
            return;
        }

        switch (pending.profile().admissionClass()) {
            case MICROSOFT -> {
                if (!event.isOnlineMode()) {
                    return;
                }

                // Preserve the actual profile produced by native Mojang authentication.
                GameProfile nativeProfile = event.getOriginalProfile();
                event.setGameProfile(nativeProfile);

                PendingAdmission resolved = pending.withExpectedProfile(nativeProfile);
                pendingByLogin.put(
                        PendingLoginKey.of(nativeProfile.getId(), event.getConnection()),
                        resolved);
            }
            case GUEST -> {
                if (event.isOnlineMode() || pending.expectedProfile() == null) {
                    return;
                }

                event.setGameProfile(pending.expectedProfile());
                pendingByLogin.put(
                        PendingLoginKey.of(pending.expectedProfile().getId(), event.getConnection()),
                        pending);
            }
            case FEDERATED, WORKLOAD -> {
                // PreLogin denies these classes until a verifier is qualified.
            }
        }
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        PendingAdmission pending = pendingByLogin.remove(
                PendingLoginKey.of(player.getUniqueId(), player));

        if (pending == null || pending.expectedProfile() == null || pending.gameIdentity() == null) {
            event.setResult(ResultedEvent.ComponentResult.denied(SESSION_MISSING));
            return;
        }

        if (!GameProfileInvariant.matchesExactly(pending.expectedProfile(), player.getGameProfile())) {
            event.setResult(ResultedEvent.ComponentResult.denied(PROFILE_CONFLICT));
            return;
        }

        switch (pending.profile().admissionClass()) {
            case MICROSOFT -> {
                if (!player.isOnlineMode() || event.getServerIdHash() == null) {
                    event.setResult(ResultedEvent.ComponentResult.denied(IDENTITY_CONFLICT));
                    return;
                }
            }
            case GUEST -> {
                if (player.isOnlineMode()) {
                    event.setResult(ResultedEvent.ComponentResult.denied(IDENTITY_CONFLICT));
                    return;
                }
            }
            case FEDERATED, WORKLOAD -> {
                event.setResult(ResultedEvent.ComponentResult.denied(UNAVAILABLE));
                return;
            }
        }

        sessions.put(
                player,
                new ActiveSession(
                        pending.profile(),
                        pending.gameIdentity(),
                        pending.generation()));
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }

        ActiveSession session = sessions.get(event.getPlayer());
        if (session == null) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        var target = event.getResult().getServer().orElse(null);
        if (target == null || !session.profile().allowsServer(target.getServerInfo().getName())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onPreTransfer(PreTransferEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }

        ActiveSession session = sessions.get(event.player());
        if (session == null || session.profile().admissionClass() != AdmissionClass.MICROSOFT) {
            event.setResult(PreTransferEvent.TransferResult.denied());
        }
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onServerConnected(ServerConnectedEvent event) {
        ActiveSession session = sessions.get(event.getPlayer());
        String serverName = event.getServer().getServerInfo().getName();

        if (session == null || !session.profile().allowsServer(serverName)) {
            logger.error(
                    "VelocityIdentity postcondition violation: player={} uuid={} server={}; disconnecting",
                    event.getPlayer().getUsername(),
                    event.getPlayer().getUniqueId(),
                    serverName);
            event.getPlayer().disconnect(ROUTE_VIOLATION);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        sessions.remove(player);
        pendingByLogin.remove(PendingLoginKey.of(player.getUniqueId(), player));
    }

    private GameIdentity allocateGuestIdentity() {
        for (int attempt = 0; attempt < 16; attempt++) {
            UUID uuid = UUID.randomUUID();
            String name = GameNames.guest(uuid);

            boolean pendingNameCollision = pendingByConnection.values().stream()
                    .map(PendingAdmission::gameIdentity)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(identity -> identity.gameName().equalsIgnoreCase(name));
            boolean pendingLoginCollision = pendingByLogin.values().stream()
                    .map(PendingAdmission::gameIdentity)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(identity -> identity.gameName().equalsIgnoreCase(name));

            if (server.getPlayer(uuid).isEmpty()
                    && server.getPlayer(name).isEmpty()
                    && !pendingNameCollision
                    && !pendingLoginCollision) {
                return new GameIdentity(uuid, name);
            }
        }
        throw new IllegalStateException("Unable to allocate a collision-free guest GameIdentity");
    }

    private static String virtualHost(InboundConnection connection) {
        return connection.getVirtualHost()
                .map(InetSocketAddress::getHostString)
                .orElse("");
    }

    private void cleanupPending() {
        long cutoff = System.nanoTime() - PENDING_MAX_AGE_NANOS;
        pendingByLogin.entrySet().removeIf(entry -> entry.getValue().createdNanos() < cutoff);
        pendingByConnection.entrySet().removeIf(entry -> entry.getValue().createdNanos() < cutoff);
    }
}
