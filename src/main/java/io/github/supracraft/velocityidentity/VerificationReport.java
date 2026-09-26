package io.github.supracraft.velocityidentity;

import java.util.List;
import java.util.Objects;

public record VerificationReport(
        VerificationStatus status,
        String planFingerprint,
        String observedEnvironmentFingerprint,
        String effectivePolicyFingerprint,
        List<String> findings) {

    public VerificationReport {
        status = Objects.requireNonNull(status, "status");
        planFingerprint = Objects.requireNonNull(planFingerprint, "planFingerprint");
        observedEnvironmentFingerprint = Objects.requireNonNull(observedEnvironmentFingerprint, "observedEnvironmentFingerprint");
        effectivePolicyFingerprint = Objects.requireNonNull(effectivePolicyFingerprint, "effectivePolicyFingerprint");
        findings = List.copyOf(findings);
    }

    public enum VerificationStatus {
        PASS,
        FAIL
    }
}
