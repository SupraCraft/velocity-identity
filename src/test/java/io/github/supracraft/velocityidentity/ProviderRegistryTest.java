package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.util.GameProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderRegistryTest {
    private static final AdmissionProfile NATIVE =
            new AdmissionProfile(
                    "native",
                    AdmissionClass.ONLINE_SESSION,
                    Set.of("*"));

    @Test
    void abstainAllowsNextProviderToClaim() {
        AtomicInteger firstAuthCalls = new AtomicInteger();
        IdentityProvider first = provider(
                "first",
                200,
                ProviderClaim.ABSTAIN,
                firstAuthCalls,
                ProviderResult.denied("first", "must not run"));
        GameProfile profile = profile();
        ProviderResult expected = ProviderResult.authenticated(
                "second",
                new CanonicalPrincipal(
                        "https://issuer.example",
                        profile.getId().toString(),
                        PrincipalKind.HUMAN),
                new GameIdentity(profile.getId(), profile.getName()),
                profile,
                false);
        IdentityProvider second = provider(
                "second",
                100,
                ProviderClaim.CLAIM,
                new AtomicInteger(),
                expected);

        ProviderResult actual = new ProviderRegistry(
                List.of(first, second))
                .authenticate(request(profile));

        assertEquals(
                ProviderDisposition.AUTHENTICATED,
                actual.disposition());
        assertEquals("second", actual.providerId());
        assertSame(profile, actual.gameProfile());
        assertEquals(0, firstAuthCalls.get());
    }

    @Test
    void claimedDenialIsTerminal() {
        AtomicInteger laterCalls = new AtomicInteger();
        IdentityProvider denying = provider(
                "denying",
                200,
                ProviderClaim.CLAIM,
                new AtomicInteger(),
                ProviderResult.denied("denying", "bad credential"));
        IdentityProvider later = provider(
                "later",
                100,
                ProviderClaim.CLAIM,
                laterCalls,
                ProviderResult.error("later", "must not run"));

        ProviderResult result = new ProviderRegistry(
                List.of(denying, later))
                .authenticate(request(profile()));

        assertEquals(
                ProviderDisposition.DENIED,
                result.disposition());
        assertEquals("denying", result.providerId());
        assertEquals(0, laterCalls.get());
    }

    @Test
    void equalPriorityOverlapIsRejected() {
        IdentityProvider a = provider(
                "a",
                100,
                ProviderClaim.ABSTAIN,
                new AtomicInteger(),
                ProviderResult.denied("a", "unused"));
        IdentityProvider b = provider(
                "b",
                100,
                ProviderClaim.ABSTAIN,
                new AtomicInteger(),
                ProviderResult.denied("b", "unused"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderRegistry(List.of(a, b)));
    }

    private static ProviderRequest request(GameProfile profile) {
        return new ProviderRequest(
                VelocityOnlineSessionProvider.MECHANISM,
                NATIVE,
                profile,
                true);
    }

    private static GameProfile profile() {
        return new GameProfile(
                UUID.fromString(
                        "11111111-2222-4333-8444-555555555555"),
                "ProviderUser",
                List.of());
    }

    private static IdentityProvider provider(
            String id,
            int priority,
            ProviderClaim claim,
            AtomicInteger authCalls,
            ProviderResult result) {
        ProviderDescriptor descriptor =
                new ProviderDescriptor(
                        id,
                        "https://" + id + ".example",
                        priority,
                        Set.of(AdmissionClass.ONLINE_SESSION),
                        Set.of(VelocityOnlineSessionProvider.MECHANISM),
                        Set.of(
                                ProviderCapability.SESSION_VERIFICATION));

        return new IdentityProvider() {
            @Override
            public ProviderDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ProviderClaim claim(ProviderRequest request) {
                return claim;
            }

            @Override
            public ProviderResult authenticate(
                    ProviderRequest request) {
                authCalls.incrementAndGet();
                return result;
            }
        };
    }
}
