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

        if (desiredPolicy.defaultProfile().admissionClass()
                == AdmissionClass.GUEST) {
            findings.add(new PlanFinding(
                    PlanFinding.Severity.BLOCKER,
                    "GUEST_DEFAULT_FORBIDDEN",
                    "Guest admission must be selected explicitly by host and cannot be the default."));
        }

        inspectProfile(
                desiredPolicy.defaultProfile(),
                "default",
                observation,
                findings);
        desiredPolicy.profilesByHost().forEach(
                (host, profile) -> inspectProfile(
                        profile,
                        host,
                        observation,
                        findings));

        if (usesNative(desiredPolicy)
                && !SessionAuthority.mojang().issuer().equals(
                        observation.sessionAuthorityIssuer())) {
            findings.add(new PlanFinding(
                    PlanFinding.Severity.INFO,
                    "CUSTOM_YGGDRASIL_SESSION_AUTHORITY",
                    "Native admission is verified by the configured process-wide Yggdrasil session authority '"
                            + observation.sessionAuthorityIssuer()
                            + "'."));
        }

        if (usesNative(desiredPolicy)
                && observation.sessionHasJoinedEndpoint()
                .startsWith("http://")) {
            findings.add(new PlanFinding(
                    PlanFinding.Severity.WARNING,
                    "SESSION_AUTHORITY_PLAINTEXT",
                    "The configured Yggdrasil hasJoined endpoint uses plaintext HTTP. Use this only for isolated qualification; production authorities should use HTTPS."));
        }

        if (observation.unknownFacts().contains(
                "player-info-forwarding-mode")) {
            findings.add(new PlanFinding(
                    PlanFinding.Severity.WARNING,
                    "FORWARDING_MODE_UNKNOWN",
                    "Velocity public API does not expose player-info-forwarding-mode; backend forwarding must be verified by another qualified sensor."));
        }

        return PolicyPlan.create(
                observation,
                currentPolicy,
                desiredPolicy,
                findings);
    }

    private static void inspectProfile(
            AdmissionProfile profile,
            String selector,
            EnvironmentObservation observation,
            List<PlanFinding> findings) {
        if (profile.admissionClass() == AdmissionClass.FEDERATED
                || profile.admissionClass()
                == AdmissionClass.WORKLOAD) {
            findings.add(new PlanFinding(
                    PlanFinding.Severity.BLOCKER,
                    "AUTHENTICATOR_UNQUALIFIED",
                    "Admission profile '" + selector
                            + "' selects "
                            + profile.admissionClass()
                            + " but no credential transport/verifier is qualified yet."));
        }

        for (String server : profile.allowedServers()) {
            if (!server.equals("*")
                    && !observation.knownServerNames()
                    .contains(server)) {
                findings.add(new PlanFinding(
                        PlanFinding.Severity.WARNING,
                        "SERVER_NOT_OBSERVED",
                        "Admission profile '" + selector
                                + "' references backend '"
                                + server
                                + "' which is not present in the current observation."));
            }
        }
    }

    private static boolean usesNative(
            AdmissionPolicy policy) {
        if (policy.defaultProfile().admissionClass()
                == AdmissionClass.NATIVE) {
            return true;
        }
        return policy.profilesByHost().values().stream()
                .anyMatch(profile ->
                        profile.admissionClass()
                                == AdmissionClass.NATIVE);
    }
}
