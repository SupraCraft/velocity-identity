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
        return plan(
                observation,
                currentPolicy,
                desiredPolicy,
                false);
    }

    public static PolicyPlan plan(
            EnvironmentObservation observation,
            AdmissionPolicy currentPolicy,
            AdmissionPolicy desiredPolicy,
            boolean workloadVerifierReady) {
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
                findings,
                workloadVerifierReady);
        desiredPolicy.profilesByHost().forEach(
                (host, profile) -> inspectProfile(
                        profile,
                        host,
                        observation,
                        findings,
                        workloadVerifierReady));

        if (usesOnlineSession(desiredPolicy)
                && !SessionAuthority.mojang().issuer().equals(
                        observation.sessionAuthorityIssuer())) {
            findings.add(new PlanFinding(
                    PlanFinding.Severity.INFO,
                    "CUSTOM_YGGDRASIL_SESSION_AUTHORITY",
                    "Online-session admission is verified by the configured process-wide Yggdrasil session authority '"
                            + observation.sessionAuthorityIssuer()
                            + "'."));
        }

        if (usesOnlineSession(desiredPolicy)
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
            List<PlanFinding> findings,
            boolean workloadVerifierReady) {
        if (profile.admissionClass() == AdmissionClass.FEDERATED) {
            findings.add(new PlanFinding(
                    PlanFinding.Severity.BLOCKER,
                    "AUTHENTICATOR_UNQUALIFIED",
                    "Admission profile '" + selector
                            + "' selects FEDERATED but no direct human federation transport/verifier is qualified."));
        }
        if (profile.admissionClass() == AdmissionClass.WORKLOAD
                && !workloadVerifierReady) {
            findings.add(new PlanFinding(
                    PlanFinding.Severity.BLOCKER,
                    "WORKLOAD_TRUST_UNAVAILABLE",
                    "Admission profile '" + selector
                            + "' selects WORKLOAD but no validated external workload trust binding is available."));
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

    private static boolean usesOnlineSession(
            AdmissionPolicy policy) {
        if (policy.defaultProfile().admissionClass()
                == AdmissionClass.ONLINE_SESSION) {
            return true;
        }
        return policy.profilesByHost().values().stream()
                .anyMatch(profile ->
                        profile.admissionClass()
                                == AdmissionClass.ONLINE_SESSION);
    }
}
