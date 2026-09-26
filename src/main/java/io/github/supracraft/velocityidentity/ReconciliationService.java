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
    private final SessionAuthority sessionAuthority;
    private final WorkloadTrustRuntime workloadTrustRuntime;

    public ReconciliationService(
            ProxyServer server,
            Path dataDirectory,
            PolicyRuntime runtime,
            AuthorityGate authorityGate) {
        this(
                server,
                dataDirectory,
                runtime,
                authorityGate,
                SessionAuthority.observeConfigured(),
                new WorkloadTrustRuntime(WorkloadTrustStore.empty()));
    }

    public ReconciliationService(
            ProxyServer server,
            Path dataDirectory,
            PolicyRuntime runtime,
            AuthorityGate authorityGate,
            SessionAuthority sessionAuthority) {
        this(
                server,
                dataDirectory,
                runtime,
                authorityGate,
                sessionAuthority,
                new WorkloadTrustRuntime(WorkloadTrustStore.empty()));
    }

    public ReconciliationService(
            ProxyServer server,
            Path dataDirectory,
            PolicyRuntime runtime,
            AuthorityGate authorityGate,
            SessionAuthority sessionAuthority,
            WorkloadTrustRuntime workloadTrustRuntime) {
        this.server = Objects.requireNonNull(server, "server");
        this.dataDirectory = Objects.requireNonNull(
                dataDirectory,
                "dataDirectory");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.authorityGate = Objects.requireNonNull(
                authorityGate,
                "authorityGate");
        this.sessionAuthority = Objects.requireNonNull(
                sessionAuthority,
                "sessionAuthority");
        this.workloadTrustRuntime = Objects.requireNonNull(
                workloadTrustRuntime,
                "workloadTrustRuntime");
    }

    public synchronized ReconciliationResult reconcile(
            String trigger) throws Exception {
        Objects.requireNonNull(trigger, "trigger");
        try {
            Files.createDirectories(dataDirectory);
            Path configPath =
                    dataDirectory.resolve("velocity-identity.properties");
            VelocityIdentityConfig desired =
                    VelocityIdentityConfig.load(configPath);
            WorkloadTrustStore desiredWorkloads =
                    WorkloadTrustStore.load(
                            dataDirectory.resolve("workloads.properties"));

            EnvironmentObservation observation =
                    VelocityObserver.observe(server, sessionAuthority);
            PolicyPlan plan = PolicyPlanner.plan(
                    observation,
                    runtime.current(),
                    desired.desiredPolicy(),
                    !desiredWorkloads.isEmpty());

            RuntimeEvidenceWriter evidence =
                    new RuntimeEvidenceWriter(dataDirectory);
            evidence.writeObservation(observation);
            evidence.writePlan(plan);

            EnvironmentObservation preApplyObservation =
                    VelocityObserver.observe(server, sessionAuthority);
            ApplyReceipt receipt =
                    runtime.apply(plan, preApplyObservation);
            evidence.writeApply(receipt);

            if (receipt.status() == ApplyReceipt.ApplyStatus.APPLIED
                    || receipt.status() == ApplyReceipt.ApplyStatus.NOOP) {
                workloadTrustRuntime.apply(desiredWorkloads);
            }

            EnvironmentObservation verificationObservation =
                    VelocityObserver.observe(server, sessionAuthority);
            VerificationReport verification = PolicyVerifier.verify(
                    plan,
                    verificationObservation,
                    runtime.current());

            WorkloadTrustStore effectiveWorkloads =
                    workloadTrustRuntime.current();
            evidence.writeWorkloadTrust(
                    effectiveWorkloads.summary());
            if (verification.status()
                    == VerificationReport.VerificationStatus.PASS
                    && !effectiveWorkloads.fingerprint().equals(
                    desiredWorkloads.fingerprint())) {
                java.util.ArrayList<String> findings =
                        new java.util.ArrayList<>(
                                verification.findings());
                findings.add("workload-trust-mismatch");
                verification = new VerificationReport(
                        VerificationReport.VerificationStatus.FAIL,
                        verification.planFingerprint(),
                        verification.observedEnvironmentFingerprint(),
                        verification.effectivePolicyFingerprint(),
                        findings);
            }
            evidence.writeVerification(verification);

            if ((receipt.status() == ApplyReceipt.ApplyStatus.APPLIED
                    || receipt.status() == ApplyReceipt.ApplyStatus.NOOP)
                    && verification.status()
                    == VerificationReport.VerificationStatus.PASS) {
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
