package io.github.supracraft.velocityidentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PolicyVerifier {
    private PolicyVerifier() {
    }

    public static VerificationReport verify(
            PolicyPlan plan,
            EnvironmentObservation freshObservation,
            AdmissionPolicy effectivePolicy) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(freshObservation, "freshObservation");
        Objects.requireNonNull(effectivePolicy, "effectivePolicy");

        List<String> findings = new ArrayList<>();
        if (!plan.observationFingerprint().equals(freshObservation.fingerprint())) {
            findings.add("environment-drift");
        }
        if (!plan.desiredPolicy().fingerprint().equals(effectivePolicy.fingerprint())) {
            findings.add("effective-policy-mismatch");
        }
        if (!plan.applicable()) {
            findings.add("plan-not-applicable");
        }

        return new VerificationReport(
                findings.isEmpty()
                        ? VerificationReport.VerificationStatus.PASS
                        : VerificationReport.VerificationStatus.FAIL,
                plan.planFingerprint(),
                freshObservation.fingerprint(),
                effectivePolicy.fingerprint(),
                findings);
    }
}
