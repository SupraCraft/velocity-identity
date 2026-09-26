package io.github.supracraft.velocityidentity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityIdentityPlugin {
    private static final Component UNAVAILABLE =
            Component.text("This admission method is not currently available.");
    private static final Component SESSION_MISSING =
            Component.text("Identity admission state is unavailable; reconnect.");

    private static final long PENDING_MAX_AGE_NANOS = 5L * 60L * 1_000_000_000L;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final PolicyRuntime runtime = new PolicyRuntime(AdmissionPolicy.microsoftOnly());

    private final Map<InboundConnection, PendingAdmission> pendingByConnection =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<UUID, PendingAdmission> pendingByUuid = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveSession> sessions = new ConcurrentHashMap<>();

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

            logger.info(
                    "Velocity Identity reconciliation status={} verify={} plan={}",
                    receipt.status(),
                    verification.status(),
                    plan.planFingerprint());

            if (receipt.status() == ApplyReceipt.ApplyStatus.BLOCKED
                    || receipt.status() == ApplyReceipt.ApplyStatus.STALE
                    || verification.status() != VerificationReport.VerificationStatus.PASS) {
                logger.warn("Velocity Identity kept or returned to its last verified effective policy.");
            }
        } catch (Exception error) {
            logger.error(
                    "Velocity Identity initialization failed; retaining fail-closed Microsoft-only policy",
                    error);
        }
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onPreLogin(PreLoginEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }

        cleanupPending();
        AdmissionProfile profile = runtime.current().select(virtualHost(event.getConnection()));

        switch (profile.admissionClass()) {
            case MICROSOFT -> {
                pendingByConnection.put(
                        event.getConnection(),
                        new PendingAdmission(profile, null, System.nanoTime()));
                event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
            }
            case GUEST -> {
                UUID uuid = UUID.randomUUID();
                GameIdentity identity = new GameIdentity(uuid, GameNames.guest(uuid));
                pendingByConnection.put(
                        event.getConnection(),
                        new PendingAdmission(profile, identity, System.nanoTime()));
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
                pendingByUuid.put(event.getGameProfile().getId(), pending);
            }
            case GUEST -> {
                if (event.isOnlineMode() || pending.gameIdentity() == null) {
                    return;
                }
                GameIdentity identity = pending.gameIdentity();
                event.setGameProfile(new GameProfile(
                        identity.gameUuid(),
                        identity.gameName(),
                        List.of()));
                pendingByUuid.put(identity.gameUuid(), pending);
            }
            case FEDERATED, WORKLOAD -> {
                // PreLogin denies these classes until a verifier is qualified.
            }
        }
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        PendingAdmission pending = pendingByUuid.remove(player.getUniqueId());
        if (pending == null) {
            event.setResult(ResultedEvent.ComponentResult.denied(SESSION_MISSING));
            return;
        }

        if (pending.profile().admissionClass() == AdmissionClass.MICROSOFT
                && event.getServerIdHash() == null) {
            event.setResult(ResultedEvent.ComponentResult.denied(SESSION_MISSING));
            return;
        }

        sessions.put(
                player.getUniqueId(),
                new ActiveSession(player.getUniqueId(), pending.profile(), pending.gameIdentity()));
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }

        ActiveSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            return;
        }

        var target = event.getResult().getServer().orElse(null);
        if (target == null || !session.profile().allowsServer(target.getServerInfo().getName())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        pendingByUuid.remove(event.getPlayer().getUniqueId());
    }

    private static String virtualHost(InboundConnection connection) {
        return connection.getVirtualHost()
                .map(InetSocketAddress::getHostString)
                .orElse("");
    }

    private void cleanupPending() {
        long cutoff = System.nanoTime() - PENDING_MAX_AGE_NANOS;
        pendingByUuid.entrySet().removeIf(entry -> entry.getValue().createdNanos() < cutoff);
        synchronized (pendingByConnection) {
            pendingByConnection.entrySet().removeIf(entry -> entry.getValue().createdNanos() < cutoff);
        }
    }
}
