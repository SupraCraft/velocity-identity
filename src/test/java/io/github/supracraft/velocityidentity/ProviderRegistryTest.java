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
    private static final AdmissionProfile ONLINE_SESSION =
            new AdmissionProfile(
                    "online-session",
                    AdmissionClass.ONLINE_SESSION,
                    Set.of("*"));

    @Test
    void abstainAllowsNextProviderToClaim() {
        AtomicInteger firstAuthCalls = new AtomicInteger();
        IdentityProvider first = provider(
                descriptor(
                        "first",
                        "https://first.example",
                        200,
                        Set.of(
                                ProviderCapability.SESSION_VERIFICATION,
                                ProviderCapability.GAME_IDENTITY)),
                ProviderClaim.ABSTAIN,
                firstAuthCalls,
                ProviderResult.denied("first", "must not run"));
        GameProfile profile = profile();
        ProviderResult expected = authenticated(
                "second",
                "https://second.example",
                profile);
        IdentityProvider second = provider(
                descriptor(
                        "second",
                        "https://second.example",
                        100,
                        Set.of(
                                ProviderCapability.SESSION_VERIFICATION,
                                ProviderCapability.GAME_IDENTITY)),
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
                descriptor(
                        "denying",
                        "https://denying.example",
                        200,
                        Set.of(
                                ProviderCapability.SESSION_VERIFICATION,
                                ProviderCapability.GAME_IDENTITY)),
                ProviderClaim.CLAIM,
                new AtomicInteger(),
                ProviderResult.denied("denying", "bad credential"));
        IdentityProvider later = provider(
                descriptor(
                        "later",
                        "https://later.example",
                        100,
                        Set.of(
                                ProviderCapability.SESSION_VERIFICATION,
                                ProviderCapability.GAME_IDENTITY)),
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
                descriptor(
                        "a",
                        "https://a.example",
                        100,
                        Set.of(ProviderCapability.SESSION_VERIFICATION)),
                ProviderClaim.ABSTAIN,
                new AtomicInteger(),
                ProviderResult.denied("a", "unused"));
        IdentityProvider b = provider(
                descriptor(
                        "b",
                        "https://b.example",
                        100,
                        Set.of(ProviderCapability.SESSION_VERIFICATION)),
                ProviderClaim.ABSTAIN,
                new AtomicInteger(),
                ProviderResult.denied("b", "unused"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderRegistry(List.of(a, b)));
    }

    @Test
    void duplicateProviderIdIsRejected() {
        IdentityProvider a = provider(
                descriptor(
                        "same",
                        "https://a.example",
                        200,
                        Set.of(ProviderCapability.SESSION_VERIFICATION)),
                ProviderClaim.ABSTAIN,
                new AtomicInteger(),
                ProviderResult.denied("same", "unused"));
        IdentityProvider b = provider(
                descriptor(
                        "same",
                        "https://b.example",
                        100,
                        Set.of(ProviderCapability.SESSION_VERIFICATION)),
                ProviderClaim.ABSTAIN,
                new AtomicInteger(),
                ProviderResult.denied("same", "unused"));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderRegistry(List.of(a, b)));
    }

    @Test
    void mismatchedProviderIdBecomesTerminalError() {
        IdentityProvider provider = provider(
                descriptor(
                        "declared",
                        "https://declared.example",
                        100,
                        Set.of(
                                ProviderCapability.SESSION_VERIFICATION,
                                ProviderCapability.GAME_IDENTITY)),
                ProviderClaim.CLAIM,
                new AtomicInteger(),
                ProviderResult.denied("different", "bad result"));

        ProviderResult result = new ProviderRegistry(
                List.of(provider))
                .authenticate(request(profile()));

        assertEquals(ProviderDisposition.ERROR, result.disposition());
        assertEquals("declared", result.providerId());
    }

    @Test
    void authenticatedIssuerMustMatchProviderTrustDomain() {
        GameProfile profile = profile();
        IdentityProvider provider = provider(
                descriptor(
                        "provider",
                        "https://declared.example",
                        100,
                        Set.of(
                                ProviderCapability.SESSION_VERIFICATION,
                                ProviderCapability.GAME_IDENTITY)),
                ProviderClaim.CLAIM,
                new AtomicInteger(),
                authenticated(
                        "provider",
                        "https://substituted.example",
                        profile));

        ProviderResult result = new ProviderRegistry(
                List.of(provider))
                .authenticate(request(profile));

        assertEquals(ProviderDisposition.ERROR, result.disposition());
        assertEquals("provider", result.providerId());
    }

    @Test
    void authenticatedProviderMustDeclareGameIdentityCapability() {
        GameProfile profile = profile();
        IdentityProvider provider = provider(
                descriptor(
                        "provider",
                        "https://provider.example",
                        100,
                        Set.of(ProviderCapability.SESSION_VERIFICATION)),
                ProviderClaim.CLAIM,
                new AtomicInteger(),
                authenticated(
                        "provider",
                        "https://provider.example",
                        profile));

        ProviderResult result = new ProviderRegistry(
                List.of(provider))
                .authenticate(request(profile));

        assertEquals(ProviderDisposition.ERROR, result.disposition());
    }

    @Test
    void authenticatedProviderMustDeclareAuthenticationOrSessionVerification() {
        GameProfile profile = profile();
        IdentityProvider provider = provider(
                descriptor(
                        "provider",
                        "https://provider.example",
                        100,
                        Set.of(ProviderCapability.GAME_IDENTITY)),
                ProviderClaim.CLAIM,
                new AtomicInteger(),
                authenticated(
                        "provider",
                        "https://provider.example",
                        profile));

        ProviderResult result = new ProviderRegistry(
                List.of(provider))
                .authenticate(request(profile));

        assertEquals(ProviderDisposition.ERROR, result.disposition());
    }

    @Test
    void claimExceptionIsTerminal() {
        AtomicInteger laterCalls = new AtomicInteger();
        ProviderDescriptor brokenDescriptor = descriptor(
                "broken",
                "https://broken.example",
                200,
                Set.of(
                        ProviderCapability.SESSION_VERIFICATION,
                        ProviderCapability.GAME_IDENTITY));
        IdentityProvider broken = new IdentityProvider() {
            @Override
            public ProviderDescriptor descriptor() {
                return brokenDescriptor;
            }

            @Override
            public ProviderClaim claim(ProviderRequest request) {
                throw new IllegalStateException("boom");
            }

            @Override
            public ProviderResult authenticate(ProviderRequest request) {
                throw new AssertionError("must not authenticate");
            }
        };
        IdentityProvider later = provider(
                descriptor(
                        "later",
                        "https://later.example",
                        100,
                        Set.of(
                                ProviderCapability.SESSION_VERIFICATION,
                                ProviderCapability.GAME_IDENTITY)),
                ProviderClaim.CLAIM,
                laterCalls,
                ProviderResult.denied("later", "must not run"));

        ProviderResult result = new ProviderRegistry(
                List.of(broken, later))
                .authenticate(request(profile()));

        assertEquals(ProviderDisposition.ERROR, result.disposition());
        assertEquals("broken", result.providerId());
        assertEquals(0, laterCalls.get());
    }

    private static ProviderRequest request(GameProfile profile) {
        return new ProviderRequest(
                VelocityOnlineSessionProvider.MECHANISM,
                ONLINE_SESSION,
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

    private static ProviderResult authenticated(
            String providerId,
            String issuer,
            GameProfile profile) {
        return ProviderResult.authenticated(
                providerId,
                new CanonicalPrincipal(
                        issuer,
                        profile.getId().toString(),
                        PrincipalKind.HUMAN),
                new GameIdentity(
                        profile.getId(),
                        profile.getName()),
                profile,
                false);
    }

    private static ProviderDescriptor descriptor(
            String id,
            String issuer,
            int priority,
            Set<ProviderCapability> capabilities) {
        return new ProviderDescriptor(
                id,
                issuer,
                priority,
                Set.of(AdmissionClass.ONLINE_SESSION),
                Set.of(VelocityOnlineSessionProvider.MECHANISM),
                capabilities);
    }

    private static IdentityProvider provider(
            ProviderDescriptor descriptor,
            ProviderClaim claim,
            AtomicInteger authCalls,
            ProviderResult result) {
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
