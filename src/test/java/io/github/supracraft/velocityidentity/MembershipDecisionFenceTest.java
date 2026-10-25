package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MembershipDecisionFenceTest {
    private static final Instant START = Instant.parse("2026-10-25T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-26T00:00:00Z");
    private static final UUID REALM = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final CanonicalPrincipal HUMAN = new CanonicalPrincipal(
            "https://sessionserver.mojang.com", "synthetic-subject", PrincipalKind.HUMAN);

    private static final class Fixture {
        private final SignedMembershipProjection.RealmVerified verified;
        private final SignedMembershipProjection.RealmWatermark witness;

        Fixture() throws Exception {
            KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var policy = new MembershipAdmissionPolicy(7, START, END,
                    Map.of(HUMAN, new MembershipAdmissionPolicy.Grant(
                            Set.of("two_rivers"), START, END)), Set.of());
            byte[] bytes = SignedMembershipProjection.canonicalBytes(policy);
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key.getPrivate());
            signature.update(SignedMembershipProjection.realmSigningBytes(bytes, REALM, REALM));
            verified = SignedMembershipProjection.verifyForRealm(bytes,
                    signature.sign(), key.getPublic(), REALM, REALM,
                    START.plusSeconds(10));
            witness = new SignedMembershipProjection.RealmWatermark(
                    REALM, REALM, new SignedMembershipProjection.Watermark(
                            policy.revision(), verified.digest(), verified.signerFingerprint()));
        }

        MembershipDecisionFence.Verdict decide(
                SignedMembershipProjection.RealmWatermark cursor,
                UUID realm, UUID audience, CanonicalPrincipal principal,
                String backend, Instant now, boolean reachable,
                boolean clock, long sessionGeneration, long effectiveGeneration) {
            return MembershipDecisionFence.decide(true, verified, cursor,
                    realm, audience, principal, backend, now, reachable, clock,
                    sessionGeneration, effectiveGeneration);
        }

        MembershipDecisionFence.Verdict normal() {
            return decide(witness, REALM, REALM, HUMAN, "two_rivers",
                    START.plusSeconds(20), true, true, 3, 3);
        }
    }

    @Test
    void realEd25519VerifiedRealmAndCurrentWitnessAllowsExplicitBackend() throws Exception {
        assertEquals(MembershipDecisionFence.Verdict.ALLOW, new Fixture().normal());
    }

    @Test
    void disabledMembershipIsNotAnAllowDecision() {
        assertEquals(MembershipDecisionFence.Verdict.NOT_ENABLED,
                MembershipDecisionFence.decide(false, null, null, null,
                        null, null, null, null, false, false, -1, -1));
    }

    @Test
    void missingAndOfflineAuthorityFailClosed() throws Exception {
        Fixture f = new Fixture();
        assertEquals(MembershipDecisionFence.Verdict.DENY_MISSING_AUTHORITY,
                MembershipDecisionFence.decide(true, null, f.witness,
                        REALM, REALM, HUMAN, "two_rivers", START, true, true, 1, 1));
        assertEquals(MembershipDecisionFence.Verdict.DENY_WITNESS_OR_CLOCK,
                f.decide(f.witness, REALM, REALM, HUMAN, "two_rivers",
                        START.plusSeconds(5), false, true, 1, 1));
        assertEquals(MembershipDecisionFence.Verdict.DENY_WITNESS_OR_CLOCK,
                f.decide(f.witness, REALM, REALM, HUMAN, "two_rivers",
                        START.plusSeconds(5), true, false, 1, 1));
    }

    @Test
    void rollbackAndGenerationMismatchAreRejected() throws Exception {
        Fixture f = new Fixture();
        var older = new SignedMembershipProjection.RealmWatermark(
                REALM, REALM, new SignedMembershipProjection.Watermark(
                        6, f.verified.digest(), f.verified.signerFingerprint()));
        assertEquals(MembershipDecisionFence.Verdict.DENY_REALM_OR_REVISION,
                f.decide(older, REALM, REALM, HUMAN, "two_rivers",
                        START.plusSeconds(5), true, true, 1, 1));
        assertEquals(MembershipDecisionFence.Verdict.DENY_STALE_GENERATION,
                f.decide(f.witness, REALM, REALM, HUMAN, "two_rivers",
                        START.plusSeconds(5), true, true, 1, 2));
    }

    @Test
    void realmAudienceAndDigestMismatchesAreRejected() throws Exception {
        Fixture f = new Fixture();
        assertEquals(MembershipDecisionFence.Verdict.DENY_REALM_OR_REVISION,
                f.decide(f.witness, OTHER, REALM, HUMAN, "two_rivers",
                        START.plusSeconds(5), true, true, 1, 1));
        assertEquals(MembershipDecisionFence.Verdict.DENY_REALM_OR_REVISION,
                f.decide(f.witness, REALM, OTHER, HUMAN, "two_rivers",
                        START.plusSeconds(5), true, true, 1, 1));
        var substituted = new SignedMembershipProjection.RealmWatermark(
                REALM, REALM, new SignedMembershipProjection.Watermark(7,
                        "0".repeat(64), f.verified.signerFingerprint()));
        assertEquals(MembershipDecisionFence.Verdict.DENY_REALM_OR_REVISION,
                f.decide(substituted, REALM, REALM, HUMAN, "two_rivers",
                        START.plusSeconds(5), true, true, 1, 1));
    }

    @Test
    void expiryWrongBackendAndUnrecognizedPrincipalAreDenied() throws Exception {
        Fixture f = new Fixture();
        assertEquals(MembershipDecisionFence.Verdict.DENY_EXPIRED_OR_UNGRANTED,
                f.decide(f.witness, REALM, REALM, HUMAN, "two_rivers",
                        END, true, true, 1, 1));
        assertEquals(MembershipDecisionFence.Verdict.DENY_EXPIRED_OR_UNGRANTED,
                f.decide(f.witness, REALM, REALM, HUMAN, "bot_gym",
                        START.plusSeconds(5), true, true, 1, 1));
        assertEquals(MembershipDecisionFence.Verdict.DENY_MISSING_AUTHORITY,
                f.decide(f.witness, REALM, REALM, null, "two_rivers",
                        START.plusSeconds(5), true, true, 1, 1));
    }
}
