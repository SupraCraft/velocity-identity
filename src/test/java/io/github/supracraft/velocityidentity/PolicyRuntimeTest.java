package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyRuntimeTest {
    @Test
    void applyIsIdempotentAndRollbackRestoresPreviousPolicy() {
        EnvironmentObservation observation = PolicyPlannerTest.observation();
        AdmissionPolicy before = AdmissionPolicy.microsoftOnly();
        AdmissionPolicy desired = new AdmissionPolicy(
                before.defaultProfile(),
                Map.of("guest.example.net",
                        new AdmissionProfile("guest", AdmissionClass.GUEST, Set.of("guest-gym"))));

        PolicyRuntime runtime = new PolicyRuntime(before);
        PolicyPlan plan = PolicyPlanner.plan(observation, before, desired);

        ApplyReceipt first = runtime.apply(plan, observation);
        ApplyReceipt second = runtime.apply(plan, observation);
        ApplyReceipt rollback = runtime.rollback(first, observation);

        assertEquals(ApplyReceipt.ApplyStatus.APPLIED, first.status());
        assertEquals(ApplyReceipt.ApplyStatus.NOOP, second.status());
        assertEquals(ApplyReceipt.ApplyStatus.ROLLED_BACK, rollback.status());
        assertEquals(before.fingerprint(), runtime.current().fingerprint());
    }

    @Test
    void changedObservationRejectsPlanAsStale() {
        EnvironmentObservation observation = PolicyPlannerTest.observation();
        AdmissionPolicy before = AdmissionPolicy.microsoftOnly();
        AdmissionPolicy desired = new AdmissionPolicy(
                before.defaultProfile(),
                Map.of("guest.example.net",
                        new AdmissionProfile("guest", AdmissionClass.GUEST, Set.of("guest-gym"))));
        PolicyPlan plan = PolicyPlanner.plan(observation, before, desired);

        EnvironmentObservation changed = new EnvironmentObservation(
                observation.proxyName(),
                observation.proxyVendor(),
                "4.2.1-SNAPSHOT",
                observation.onlineMode(),
                observation.configuredServers(),
                observation.registeredServers(),
                observation.attemptConnectionOrder(),
                observation.forcedHosts(),
                observation.loadedPlugins(),
                observation.unknownFacts());

        PolicyRuntime runtime = new PolicyRuntime(before);
        assertEquals(ApplyReceipt.ApplyStatus.STALE, runtime.apply(plan, changed).status());
    }
}
