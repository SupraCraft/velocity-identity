package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdmissionPolicyTest {
    @Test
    void hostSelectionIsCaseAndTrailingDotInsensitive() {
        AdmissionProfile microsoft = new AdmissionProfile("default", AdmissionClass.MICROSOFT, Set.of("*"));
        AdmissionProfile guest = new AdmissionProfile("guest", AdmissionClass.GUEST, Set.of("guest-gym"));
        AdmissionPolicy policy = new AdmissionPolicy(microsoft, Map.of("Guest.Example.NET.", guest));

        assertEquals(AdmissionClass.GUEST, policy.select("guest.example.net").admissionClass());
        assertEquals(AdmissionClass.GUEST, policy.select("GUEST.EXAMPLE.NET.").admissionClass());
        assertEquals(AdmissionClass.MICROSOFT, policy.select("play.example.net").admissionClass());
    }

    @Test
    void equivalentPoliciesHaveSameFingerprint() {
        AdmissionProfile microsoft = new AdmissionProfile("default", AdmissionClass.MICROSOFT, Set.of("B", "a"));
        AdmissionPolicy left = new AdmissionPolicy(microsoft, Map.of());
        AdmissionPolicy right = new AdmissionPolicy(
                new AdmissionProfile("default", AdmissionClass.MICROSOFT, Set.of("a", "b")),
                Map.of());

        assertEquals(left.fingerprint(), right.fingerprint());
    }
}
