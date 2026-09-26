package io.github.supracraft.velocityidentity;

import java.util.ArrayList;
import java.util.List;

public final class PolicyPlanner {
    private PolicyPlanner() {
    }

    public static PolicyPlan plan(
            EnvironmentObservation observation,
            AdmissionPolicy currentPolicy,
            AdmissionPolicy desiredPolicy) {
        List<PlanFinding> findings = new ArrayList<>();

        if (desiredPolicy.defaultProfile().admissionClass() == AdmissionClass.GUEST) {
            findings.add(new PlanFinding(
                    PlanFinding.Severity.BLOCKER,
                    "GUEST_DEFAULT_FORBIDDEN",
                    "Guest admission must be selected explicitly by host and cannot be the default."));
        }

        inspectProfile(desiredPolicy.defaultProfile(), "default", observation, findings);
        desiredPolicy.profilesByHost().forEach(
                (host, profile) -> inspectProfile(profile, host, observation, findings));

        if (observation.unknownFacts().contains("player-info-forwarding-mode")) {
            findings.add(new PlanFinding(
                    PlanFinding.Severity.WARNING,
                    "FORWARDING_MODE_UNKNOWN",
                    "Velocity public API does not expose player-info-forwarding-mode; backend forwarding must be verified by another qualified sensor."));
        }

        return PolicyPlan.create(observation, currentPolicy, desiredPolicy, findings);
    }

    private static void inspectProfile(
            AdmissionProfile profile,
            String selector,
            EnvironmentObservation observation,
            List<PlanFinding> findings) {
        if (profile.admissionClass() == AdmissionClass.FEDERATED
                || profile.admissionClass() == AdmissionClass.WORKLOAD) {
            findings.add(new PlanFinding(
                    PlanFinding.Severity.BLOCKER,
                    "AUTHENTICATOR_UNQUALIFIED",
                    "Admission profile '" + selector + "' selects " + profile.admissionClass()
                            + " but no credential transport/verifier is qualified yet."));
        }

        for (String server : profile.allowedServers()) {
            if (!server.equals("*") && !observation.knownServerNames().contains(server)) {
                findings.add(new PlanFinding(
                        PlanFinding.Severity.WARNING,
                        "SERVER_NOT_OBSERVED",
                        "Admission profile '" + selector + "' references backend '" + server
                                + "' which is not present in the current observation."));
            }
        }
    }
}
