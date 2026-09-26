package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyPlannerTest {
    @Test
    void deterministicPlanForEquivalentInputs() {
        EnvironmentObservation observation = observation();
        AdmissionPolicy before = AdmissionPolicy.microsoftOnly();
        AdmissionPolicy desired = new AdmissionPolicy(
                before.defaultProfile(),
                Map.of("guest.example.net",
                        new AdmissionProfile("guest", AdmissionClass.GUEST, Set.of("guest-gym"))));

        PolicyPlan first = PolicyPlanner.plan(observation, before, desired);
        PolicyPlan second = PolicyPlanner.plan(observation, before, desired);

        assertEquals(first.planFingerprint(), second.planFingerprint());
        assertTrue(first.applicable());
    }

    @Test
    void guestDefaultIsBlocked() {
        EnvironmentObservation observation = observation();
        AdmissionPolicy desired = new AdmissionPolicy(
                new AdmissionProfile("guest", AdmissionClass.GUEST, Set.of("guest-gym")),
                Map.of());

        PolicyPlan plan = PolicyPlanner.plan(observation, AdmissionPolicy.microsoftOnly(), desired);

        assertFalse(plan.applicable());
        assertTrue(plan.findings().stream().anyMatch(f -> f.code().equals("GUEST_DEFAULT_FORBIDDEN")));
    }

    @Test
    void unqualifiedCredentialedPathIsBlocked() {
        EnvironmentObservation observation = observation();
        AdmissionPolicy before = AdmissionPolicy.microsoftOnly();
        AdmissionPolicy desired = new AdmissionPolicy(
                before.defaultProfile(),
                Map.of("actors.example.net",
                        new AdmissionProfile("actors", AdmissionClass.WORKLOAD, Set.of("gym"))));

        PolicyPlan plan = PolicyPlanner.plan(observation, before, desired);

        assertFalse(plan.applicable());
        assertTrue(plan.findings().stream().anyMatch(f -> f.code().equals("AUTHENTICATOR_UNQUALIFIED")));
    }

    static EnvironmentObservation observation() {
        return new EnvironmentObservation(
                "Velocity",
                "PaperMC",
                "4.2.0",
                true,
                Map.of("lobby", "127.0.0.1:25566", "guest-gym", "127.0.0.1:25567", "gym", "127.0.0.1:25568"),
                Map.of("lobby", "/127.0.0.1:25566", "guest-gym", "/127.0.0.1:25567", "gym", "/127.0.0.1:25568"),
                List.of("lobby"),
                Map.of(),
                Map.of("velocity", "4.2.0"),
                Set.of("player-info-forwarding-mode"));
    }
}
