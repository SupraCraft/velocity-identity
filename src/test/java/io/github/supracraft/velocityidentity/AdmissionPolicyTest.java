package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdmissionPolicyTest {
    @Test
    void hostSelectionIsCaseAndTrailingDotInsensitive() {
        AdmissionProfile nativeProfile =
                new AdmissionProfile(
                        "default",
                        AdmissionClass.NATIVE,
                        Set.of("*"));
        AdmissionProfile guest =
                new AdmissionProfile(
                        "guest",
                        AdmissionClass.GUEST,
                        Set.of("guest-gym"));
        AdmissionPolicy policy =
                new AdmissionPolicy(
                        nativeProfile,
                        Map.of("Guest.Example.NET.", guest));

        assertEquals(
                AdmissionClass.GUEST,
                policy.select("guest.example.net").admissionClass());
        assertEquals(
                AdmissionClass.GUEST,
                policy.select("GUEST.EXAMPLE.NET.").admissionClass());
        assertEquals(
                AdmissionClass.NATIVE,
                policy.select("play.example.net").admissionClass());
    }

    @Test
    void equivalentPoliciesHaveSameFingerprint() {
        AdmissionProfile nativeProfile =
                new AdmissionProfile(
                        "default",
                        AdmissionClass.NATIVE,
                        Set.of("B", "a"));
        AdmissionPolicy left =
                new AdmissionPolicy(nativeProfile, Map.of());
        AdmissionPolicy right =
                new AdmissionPolicy(
                        new AdmissionProfile(
                                "default",
                                AdmissionClass.NATIVE,
                                Set.of("a", "b")),
                        Map.of());

        assertEquals(
                left.fingerprint(),
                right.fingerprint());
    }
}
