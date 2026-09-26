package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyVerifierTest {
    @Test
    void independentVerifyPassesOnlyForPlannedPolicyAndObservation() {
        EnvironmentObservation observation = PolicyPlannerTest.observation();
        AdmissionPolicy before = AdmissionPolicy.microsoftOnly();
        AdmissionPolicy desired = new AdmissionPolicy(
                before.defaultProfile(),
                Map.of("guest.example.net",
                        new AdmissionProfile("guest", AdmissionClass.GUEST, Set.of("guest-gym"))));
        PolicyPlan plan = PolicyPlanner.plan(observation, before, desired);

        assertEquals(
                VerificationReport.VerificationStatus.PASS,
                PolicyVerifier.verify(plan, observation, desired).status());

        assertEquals(
                VerificationReport.VerificationStatus.FAIL,
                PolicyVerifier.verify(plan, observation, before).status());
    }
}
