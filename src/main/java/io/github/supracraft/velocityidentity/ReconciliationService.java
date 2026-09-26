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

        authorityGate.beginReconciliation();
        WorkloadTrustStore beforeWorkloads =
                workloadTrustRuntime.current();
        ApplyReceipt receipt = null;
        EnvironmentObservation rollbackObservation = null;
        RuntimeEvidenceWriter evidence = null;

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
            rollbackObservation = observation;
            PolicyPlan plan = PolicyPlanner.plan(
                    observation,
                    runtime.current(),
                    desired.desiredPolicy(),
                    !desiredWorkloads.isEmpty());

            evidence = new RuntimeEvidenceWriter(dataDirectory);
            evidence.writeObservation(observation);
            evidence.writePlan(plan);

            EnvironmentObservation preApplyObservation =
                    VelocityObserver.observe(server, sessionAuthority);
            rollbackObservation = preApplyObservation;
            receipt = runtime.apply(plan, preApplyObservation);
            evidence.writeApply(receipt);

            if (receipt.status() == ApplyReceipt.ApplyStatus.APPLIED
                    || receipt.status() == ApplyReceipt.ApplyStatus.NOOP) {
                workloadTrustRuntime.apply(desiredWorkloads);
            }

            EnvironmentObservation verificationObservation =
                    VelocityObserver.observe(server, sessionAuthority);
            rollbackObservation = verificationObservation;
            VerificationReport verification = PolicyVerifier.verify(
                    plan,
                    verificationObservation,
                    runtime.current());

            WorkloadTrustStore effectiveWorkloads =
                    workloadTrustRuntime.current();
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

            boolean verified =
                    (receipt.status()
                            == ApplyReceipt.ApplyStatus.APPLIED
                            || receipt.status()
                            == ApplyReceipt.ApplyStatus.NOOP)
                            && verification.status()
                            == VerificationReport.VerificationStatus.PASS;

            if (verified) {
                evidence.writeWorkloadTrust(
                        effectiveWorkloads.summary());
                authorityGate.markVerified();
            } else {
                restoreLastKnownGood(
                        receipt,
                        beforeWorkloads,
                        rollbackObservation,
                        evidence);
                authorityGate.markFailure();
            }

            return new ReconciliationResult(
                    trigger,
                    authorityGate.readiness(),
                    receipt.status(),
                    verification.status(),
                    plan.planFingerprint());
        } catch (Exception error) {
            boolean recovered = false;
            try {
                restoreLastKnownGood(
                        receipt,
                        beforeWorkloads,
                        rollbackObservation,
                        evidence);
                recovered = true;
            } catch (Exception rollbackError) {
                error.addSuppressed(rollbackError);
            }
            if (recovered) {
                authorityGate.markFailure();
            } else {
                authorityGate.markUnrecoverableFailure();
            }
            throw error;
        }
    }

    private void restoreLastKnownGood(
            ApplyReceipt receipt,
            WorkloadTrustStore beforeWorkloads,
            EnvironmentObservation observation,
            RuntimeEvidenceWriter evidence) throws Exception {
        if (receipt != null
                && receipt.status()
                == ApplyReceipt.ApplyStatus.APPLIED) {
            EnvironmentObservation rollbackObservation =
                    observation != null
                            ? observation
                            : VelocityObserver.observe(
                            server,
                            sessionAuthority);
            ApplyReceipt rollback =
                    runtime.rollback(
                            receipt,
                            rollbackObservation);
            if (evidence != null) {
                evidence.writeRollback(rollback);
            }
            if (rollback.status()
                    != ApplyReceipt.ApplyStatus.ROLLED_BACK) {
                throw new IllegalStateException(
                        "Unable to restore last verified admission policy: "
                                + rollback.status());
            }
        }

        workloadTrustRuntime.apply(beforeWorkloads);
        if (evidence != null) {
            evidence.writeWorkloadTrust(
                    beforeWorkloads.summary());
        }
    }
}
