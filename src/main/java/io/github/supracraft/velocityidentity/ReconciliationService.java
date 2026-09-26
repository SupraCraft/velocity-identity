package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.proxy.ProxyServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ReconciliationService {
    private final ProxyServer server;
    private final Path dataDirectory;
    private final PolicyRuntime runtime;
    private final AuthorityGate authorityGate;

    public ReconciliationService(
            ProxyServer server,
            Path dataDirectory,
            PolicyRuntime runtime,
            AuthorityGate authorityGate) {
        this.server = Objects.requireNonNull(server, "server");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.authorityGate = Objects.requireNonNull(authorityGate, "authorityGate");
    }

    public synchronized ReconciliationResult reconcile(String trigger) throws Exception {
        Objects.requireNonNull(trigger, "trigger");
        try {
            Files.createDirectories(dataDirectory);
            Path configPath = dataDirectory.resolve("velocity-identity.properties");
            VelocityIdentityConfig desired = VelocityIdentityConfig.load(configPath);

            EnvironmentObservation observation = VelocityObserver.observe(server);
            PolicyPlan plan =
                    PolicyPlanner.plan(observation, runtime.current(), desired.desiredPolicy());

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

            if ((receipt.status() == ApplyReceipt.ApplyStatus.APPLIED
                    || receipt.status() == ApplyReceipt.ApplyStatus.NOOP)
                    && verification.status() == VerificationReport.VerificationStatus.PASS) {
                authorityGate.markVerified();
            } else {
                authorityGate.markFailure();
            }

            return new ReconciliationResult(
                    trigger,
                    authorityGate.readiness(),
                    receipt.status(),
                    verification.status(),
                    plan.planFingerprint());
        } catch (Exception error) {
            authorityGate.markFailure();
            throw error;
        }
    }
}
