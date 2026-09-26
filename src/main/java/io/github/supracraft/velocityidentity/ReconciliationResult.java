package io.github.supracraft.velocityidentity;

import java.util.Objects;

public record ReconciliationResult(
        String trigger,
        AuthorityGate.Readiness readiness,
        ApplyReceipt.ApplyStatus applyStatus,
        VerificationReport.VerificationStatus verificationStatus,
        String planFingerprint) {

    public ReconciliationResult {
        trigger = Objects.requireNonNull(trigger, "trigger");
        readiness = Objects.requireNonNull(readiness, "readiness");
        applyStatus = Objects.requireNonNull(applyStatus, "applyStatus");
        verificationStatus = Objects.requireNonNull(verificationStatus, "verificationStatus");
        planFingerprint = Objects.requireNonNull(planFingerprint, "planFingerprint");
    }

    public boolean verified() {
        return (applyStatus == ApplyReceipt.ApplyStatus.APPLIED
                || applyStatus == ApplyReceipt.ApplyStatus.NOOP)
                && verificationStatus == VerificationReport.VerificationStatus.PASS;
    }
}
