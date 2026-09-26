package io.github.supracraft.velocityidentity;

import java.util.List;
import java.util.Objects;

public record PolicyPlan(
        String observationFingerprint,
        String beforePolicyFingerprint,
        AdmissionPolicy desiredPolicy,
        List<PlanFinding> findings,
        String planFingerprint) {

    public PolicyPlan {
        observationFingerprint = Objects.requireNonNull(observationFingerprint, "observationFingerprint");
        beforePolicyFingerprint = Objects.requireNonNull(beforePolicyFingerprint, "beforePolicyFingerprint");
        desiredPolicy = Objects.requireNonNull(desiredPolicy, "desiredPolicy");
        findings = findings.stream().sorted().toList();
        String expected = calculateFingerprint(
                observationFingerprint, beforePolicyFingerprint, desiredPolicy, findings);
        if (planFingerprint == null) {
            planFingerprint = expected;
        } else if (!planFingerprint.equals(expected)) {
            throw new IllegalArgumentException("planFingerprint does not match plan content");
        }
    }

    public static PolicyPlan create(
            EnvironmentObservation observation,
            AdmissionPolicy beforePolicy,
            AdmissionPolicy desiredPolicy,
            List<PlanFinding> findings) {
        return new PolicyPlan(
                observation.fingerprint(),
                beforePolicy.fingerprint(),
                desiredPolicy,
                findings,
                null);
    }

    public boolean applicable() {
        return findings.stream().noneMatch(finding -> finding.severity() == PlanFinding.Severity.BLOCKER);
    }

    private static String calculateFingerprint(
            String observationFingerprint,
            String beforePolicyFingerprint,
            AdmissionPolicy desiredPolicy,
            List<PlanFinding> findings) {
        StringBuilder canonical = new StringBuilder()
                .append(observationFingerprint).append('\n')
                .append(beforePolicyFingerprint).append('\n')
                .append(desiredPolicy.fingerprint()).append('\n');
        findings.stream().sorted().forEach(finding -> canonical
                .append(finding.severity()).append('|')
                .append(finding.code()).append('|')
                .append(finding.detail()).append('\n'));
        return Digests.sha256(canonical.toString());
    }
}
