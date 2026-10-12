package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RealmBoundMembershipTest {
    private static final UUID REALM_A = UUID.fromString(
            "4846b57a-d54d-4ab7-8861-27ea7bdf84b7");
    private static final UUID REALM_B = UUID.fromString(
            "995b2f9b-5372-491d-b049-3fae544e24fe");
    private static final Instant START = Instant.parse("2026-10-12T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-13T00:00:00Z");
    private static final Instant NOW = START.plusSeconds(15);

    private static MembershipAdmissionPolicy policy(long revision) {
        var player = new CanonicalPrincipal(
                "https://sessionserver.mojang.com", "same-uuid",
                PrincipalKind.HUMAN);
        var worker = new CanonicalPrincipal(
                "urn:supracraft:vip:workload", "same-subject",
                PrincipalKind.WORKLOAD);
        return new MembershipAdmissionPolicy(revision, START, END,
                Map.of(player, new MembershipAdmissionPolicy.Grant(
                        Set.of("two_rivers"), START, END),
                        worker, new MembershipAdmissionPolicy.Grant(
                        Set.of("bot_gym"), START, END)), Set.of());
    }

    private static KeyPair signer() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static byte[] sign(KeyPair key, byte[] bytes) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(bytes);
        return signer.sign();
    }

    private static byte[] v2Sig(KeyPair key, byte[] canonical,
                                UUID issuingRealm, UUID intendedRealm)
            throws Exception {
        return sign(key, SignedMembershipProjection.realmSigningBytes(
                canonical, issuingRealm, intendedRealm));
    }

    @Test
    void exactRealmAndAudienceAreRequiredWithoutChangingMinecraftAuth()
            throws Exception {
        KeyPair key = signer();
        byte[] payload = SignedMembershipProjection.canonicalBytes(policy(9));
        byte[] signature = v2Sig(key, payload, REALM_A, REALM_A);
        var verified = SignedMembershipProjection.verifyForRealm(
                payload, signature, key.getPublic(), REALM_A, REALM_A, NOW);
        assertEquals(REALM_A, verified.realmId());
        assertEquals(REALM_A, verified.audienceRealmId());
        assertEquals(9, verified.policy().revision());
        assertEquals(MembershipAdmissionPolicy.Decision.ALLOW,
                verified.policy().decide(new CanonicalPrincipal(
                        "https://sessionserver.mojang.com", "same-uuid",
                        PrincipalKind.HUMAN), "two_rivers", NOW));
    }

    @Test
    void twoInstancesWithSameKeyUsernameUuidAndWorldCannotCrossAuthorize()
            throws Exception {
        KeyPair key = signer();
        byte[] payload = SignedMembershipProjection.canonicalBytes(policy(9));
        byte[] signature = v2Sig(key, payload, REALM_A, REALM_A);
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verifyForRealm(
                        payload, signature, key.getPublic(),
                        REALM_B, REALM_B, NOW));
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verifyForRealm(
                        payload, signature, key.getPublic(),
                        REALM_A, REALM_B, NOW));
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verifyForRealm(
                        payload, signature, key.getPublic(),
                        REALM_B, REALM_A, NOW));
    }

    @Test
    void legacyV1SignatureIsNotAcceptedAsRealmBoundV2()
            throws Exception {
        KeyPair key = signer();
        byte[] payload = SignedMembershipProjection.canonicalBytes(policy(9));
        byte[] legacySignature = sign(key, payload);
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verifyForRealm(
                        payload, legacySignature, key.getPublic(),
                        REALM_A, REALM_A, NOW));
        byte[] v2 = v2Sig(key, payload, REALM_A, REALM_A);
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verify(
                        payload, v2, key.getPublic(), NOW));
    }

    @Test
    void realmScopedRevisionFenceRejectsWrongAudienceAndEquivocation()
            throws Exception {
        KeyPair key = signer();
        byte[] oldBytes = SignedMembershipProjection.canonicalBytes(policy(9));
        var oldVerified = SignedMembershipProjection.verifyForRealm(
                oldBytes, v2Sig(key, oldBytes, REALM_A, REALM_A),
                key.getPublic(), REALM_A, REALM_A, NOW);
        var cursor = new SignedMembershipProjection.RealmWatermark(
                REALM_A, REALM_A, new SignedMembershipProjection.Watermark(
                        9, oldVerified.digest(), oldVerified.signerFingerprint()));
        assertEquals(cursor, cursor.advance(oldVerified));

        byte[] newerBytes = SignedMembershipProjection.canonicalBytes(policy(10));
        var newer = SignedMembershipProjection.verifyForRealm(
                newerBytes, v2Sig(key, newerBytes, REALM_A, REALM_A),
                key.getPublic(), REALM_A, REALM_A, NOW);
        assertEquals(10, cursor.advance(newer).cursor().revision());

        var forB = SignedMembershipProjection.verifyForRealm(
                newerBytes, v2Sig(key, newerBytes, REALM_A, REALM_B),
                key.getPublic(), REALM_A, REALM_B, NOW);
        assertThrows(SecurityException.class, () -> cursor.advance(forB));

        var changed = new MembershipAdmissionPolicy(9, START, END,
                Map.of(), Set.of(new CanonicalPrincipal(
                        "urn:supracraft:vip:workload", "same-subject",
                        PrincipalKind.WORKLOAD)));
        byte[] altered = SignedMembershipProjection.canonicalBytes(changed);
        var equivocating = SignedMembershipProjection.verifyForRealm(
                altered, v2Sig(key, altered, REALM_A, REALM_A),
                key.getPublic(), REALM_A, REALM_A, NOW);
        assertThrows(SecurityException.class, () -> cursor.advance(equivocating));
    }

    @Test
    void invalidSignatureTamperAndExpiredPayloadStillFailClosed()
            throws Exception {
        KeyPair key = signer();
        byte[] policy = SignedMembershipProjection.canonicalBytes(policy(9));
        byte[] signature = v2Sig(key, policy, REALM_A, REALM_A);
        byte[] tampered = Arrays.copyOf(policy, policy.length);
        tampered[tampered.length - 4] ^= 1;
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verifyForRealm(
                        tampered, signature, key.getPublic(), REALM_A, REALM_A, NOW));
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verifyForRealm(
                        policy, signature, key.getPublic(), REALM_A, REALM_A, END));
    }

    @Test
    void differentRealmContextProducesDifferentAuthenticatedDigest()
            throws Exception {
        KeyPair key = signer();
        byte[] policy = SignedMembershipProjection.canonicalBytes(policy(9));
        var a = SignedMembershipProjection.verifyForRealm(
                policy, v2Sig(key, policy, REALM_A, REALM_A),
                key.getPublic(), REALM_A, REALM_A, NOW);
        var b = SignedMembershipProjection.verifyForRealm(
                policy, v2Sig(key, policy, REALM_B, REALM_B),
                key.getPublic(), REALM_B, REALM_B, NOW);
        assertNotEquals(a.digest(), b.digest());
    }
}
