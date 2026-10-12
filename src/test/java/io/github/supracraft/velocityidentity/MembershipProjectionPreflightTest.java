package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MembershipProjectionPreflightTest {
    private static final Instant START = Instant.parse("2026-10-12T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-13T00:00:00Z");
    private static final Instant NOW = START.plusSeconds(10);
    private static final CanonicalPrincipal HUMAN = new CanonicalPrincipal(
            "https://sessionserver.mojang.com", "human-uuid", PrincipalKind.HUMAN);
    private static final CanonicalPrincipal AGENT = new CanonicalPrincipal(
            "urn:supracraft:vip:workload", "agents/two-rivers/a1", PrincipalKind.WORKLOAD);

    private static MembershipAdmissionPolicy sample(long revision) {
        return new MembershipAdmissionPolicy(revision, START, END,
                Map.of(HUMAN, new MembershipAdmissionPolicy.Grant(
                                Set.of("two_rivers"), START, END),
                        AGENT, new MembershipAdmissionPolicy.Grant(
                                Set.of("bot_gym"), START, END)),
                Set.of());
    }

    private static byte[] sign(KeyPair key, byte[] bytes) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key.getPrivate());
        signature.update(bytes);
        return signature.sign();
    }

    private static KeyPair key() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static SignedMembershipProjection.Watermark trustedCursor(
            KeyPair key, MembershipAdmissionPolicy policy) throws Exception {
        byte[] bytes = SignedMembershipProjection.canonicalBytes(policy);
        var verified = SignedMembershipProjection.verify(
                bytes, sign(key, bytes), key.getPublic(), NOW);
        return new SignedMembershipProjection.Watermark(
                verified.policy().revision(), verified.digest(),
                verified.signerFingerprint());
    }

    @Test
    void twoIdenticalZeroMutationPlansWithIndependentObservation() throws Exception {
        KeyPair issuer = key();
        var cursor = trustedCursor(issuer, sample(7));
        byte[] bytes = SignedMembershipProjection.canonicalBytes(sample(8));
        byte[] signed = sign(issuer, bytes);
        byte[] before = Arrays.copyOf(bytes, bytes.length);
        byte[] signatureBefore = Arrays.copyOf(signed, signed.length);

        var first = MembershipProjectionPreflight.plan(
                bytes, signed, issuer.getPublic(), cursor, NOW);
        var second = MembershipProjectionPreflight.plan(
                bytes, signed, issuer.getPublic(), cursor, NOW);
        var independentlyObserved = MembershipProjectionPreflight.observe(
                Arrays.copyOf(bytes, bytes.length),
                Arrays.copyOf(signed, signed.length),
                issuer.getPublic(), cursor);

        assertTrue(first.isReadyForReconciliation());
        assertEquals(first, second);
        assertEquals(first.observationFingerprint(),
                independentlyObserved.fingerprint());
        assertEquals(8L, first.proposedRevision());
        assertEquals(7L, cursor.revision());
        assertArrayEquals(before, bytes);
        assertArrayEquals(signatureBefore, signed);
    }

    @Test
    void explicitlyMissingExternalWatermarkFailsClosed() throws Exception {
        KeyPair issuer = key();
        byte[] bytes = SignedMembershipProjection.canonicalBytes(sample(8));
        var planned = MembershipProjectionPreflight.plan(
                bytes, sign(issuer, bytes), issuer.getPublic(), null, NOW);
        assertEquals(MembershipProjectionPreflight.Result.BLOCKED,
                planned.result());
        assertEquals(MembershipProjectionPreflight.Finding.MISSING_TRUSTED_INPUT,
                planned.finding());
    }

    @Test
    void oldPolicyOrSameRevisionEquivocationCannotPlanReady() throws Exception {
        KeyPair issuer = key();
        var cursor = trustedCursor(issuer, sample(8));
        byte[] rollback = SignedMembershipProjection.canonicalBytes(sample(7));
        var old = MembershipProjectionPreflight.plan(
                rollback, sign(issuer, rollback), issuer.getPublic(), cursor, NOW);
        assertEquals(MembershipProjectionPreflight.Finding.WATERMARK_REJECTED,
                old.finding());

        var changed = new MembershipAdmissionPolicy(8, START, END,
                Map.of(HUMAN, new MembershipAdmissionPolicy.Grant(
                        Set.of("other"), START, END)), Set.of(AGENT));
        byte[] equivocation = SignedMembershipProjection.canonicalBytes(changed);
        var plan = MembershipProjectionPreflight.plan(
                equivocation, sign(issuer, equivocation),
                issuer.getPublic(), cursor, NOW);
        assertFalse(plan.isReadyForReconciliation());
        assertEquals(MembershipProjectionPreflight.Finding.WATERMARK_REJECTED,
                plan.finding());
    }

    @Test
    void substitutedSignerAndTamperingAreRejected() throws Exception {
        KeyPair issuer = key();
        KeyPair other = key();
        var cursor = trustedCursor(issuer, sample(7));
        byte[] bytes = SignedMembershipProjection.canonicalBytes(sample(8));
        var substituted = MembershipProjectionPreflight.plan(
                bytes, sign(other, bytes), other.getPublic(), cursor, NOW);
        assertEquals(MembershipProjectionPreflight.Finding.WATERMARK_REJECTED,
                substituted.finding());
        var forged = MembershipProjectionPreflight.plan(
                bytes, sign(other, bytes), issuer.getPublic(), cursor, NOW);
        assertEquals(MembershipProjectionPreflight.Finding.CANDIDATE_REJECTED,
                forged.finding());
        byte[] tampered = Arrays.copyOf(bytes, bytes.length);
        tampered[5] ^= 1;
        assertEquals(MembershipProjectionPreflight.Finding.CANDIDATE_REJECTED,
                MembershipProjectionPreflight.plan(
                        tampered, sign(issuer, bytes),
                        issuer.getPublic(), cursor, NOW).finding());
    }

    @Test
    void expiredPolicyAndMissingSignerAreBlocked() throws Exception {
        KeyPair issuer = key();
        var cursor = trustedCursor(issuer, sample(7));
        byte[] bytes = SignedMembershipProjection.canonicalBytes(sample(8));
        byte[] signed = sign(issuer, bytes);
        var expired = MembershipProjectionPreflight.plan(
                bytes, signed, issuer.getPublic(), cursor, END);
        assertEquals(MembershipProjectionPreflight.Finding.CANDIDATE_REJECTED,
                expired.finding());
        var missing = MembershipProjectionPreflight.plan(
                bytes, signed, null, cursor, NOW);
        assertEquals(MembershipProjectionPreflight.Finding.MISSING_TRUSTED_INPUT,
                missing.finding());
    }

    @Test
    void samePolicyReplayIsIdempotentButDoesNotAdvanceWatermark() throws Exception {
        KeyPair issuer = key();
        var cursor = trustedCursor(issuer, sample(7));
        byte[] bytes = SignedMembershipProjection.canonicalBytes(sample(7));
        var plan = MembershipProjectionPreflight.plan(
                bytes, sign(issuer, bytes), issuer.getPublic(), cursor, NOW);
        assertTrue(plan.isReadyForReconciliation());
        assertEquals(cursor.revision(), plan.proposedRevision());
        assertEquals(cursor.digest(), plan.proposedDigest());
    }

    @Test
    void planFingerprintBindsActualObservedInputAndTimeValidity() throws Exception {
        KeyPair issuer = key();
        var cursor = trustedCursor(issuer, sample(7));
        byte[] bytes = SignedMembershipProjection.canonicalBytes(sample(8));
        byte[] signature = sign(issuer, bytes);
        var ready = MembershipProjectionPreflight.plan(
                bytes, signature, issuer.getPublic(), cursor, NOW);
        var expired = MembershipProjectionPreflight.plan(
                bytes, signature, issuer.getPublic(), cursor, END);
        assertNotEquals(ready.planFingerprint(), expired.planFingerprint());
        assertEquals(ready.observationFingerprint(), expired.observationFingerprint());
    }
}
