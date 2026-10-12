package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignedMembershipProjectionTest {
    private static final Instant START = Instant.parse("2026-10-12T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-13T00:00:00Z");
    private static final CanonicalPrincipal HUMAN = new CanonicalPrincipal(
            "https://sessionserver.mojang.com", "5c5d-remote-test", PrincipalKind.HUMAN);
    private static final CanonicalPrincipal WORKLOAD = new CanonicalPrincipal(
            "urn:supracraft:vip:workload", "agents/two-rivers/builder-01",
            PrincipalKind.WORKLOAD);

    private static MembershipAdmissionPolicy snapshot(long revision) {
        return new MembershipAdmissionPolicy(revision, START, END,
                Map.of(
                        HUMAN, new MembershipAdmissionPolicy.Grant(
                                Set.of("two_rivers", "lobby"), START, END),
                        WORKLOAD, new MembershipAdmissionPolicy.Grant(
                                Set.of("bot_gym"), START, END)),
                Set.of());
    }

    private static KeyPair key() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static byte[] sign(byte[] canonical, KeyPair key) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(canonical);
        return signer.sign();
    }

    @Test
    void realEd25519SignatureVerifiesTypedPrincipalAndScope() throws Exception {
        KeyPair issuer = key();
        byte[] bytes = SignedMembershipProjection.canonicalBytes(snapshot(7));
        var verified = SignedMembershipProjection.verify(
                bytes, sign(bytes, issuer), issuer.getPublic(), START);
        assertEquals(7L, verified.policy().revision());
        assertEquals(MembershipAdmissionPolicy.Decision.ALLOW,
                verified.policy().decide(HUMAN, "two_rivers", START));
        assertEquals(MembershipAdmissionPolicy.Decision.DENY,
                verified.policy().decide(WORKLOAD, "two_rivers", START));
        assertEquals(64, verified.digest().length());
        assertEquals(64, verified.signerFingerprint().length());
    }

    @Test
    void reorderedInputsYieldByteIdenticalCanonicalBytes() {
        var shuffled = new MembershipAdmissionPolicy(
                7, START, END,
                Map.of(WORKLOAD, snapshot(7).grants().get(WORKLOAD),
                        HUMAN, snapshot(7).grants().get(HUMAN)),
                Set.of());
        assertArrayEquals(SignedMembershipProjection.canonicalBytes(snapshot(7)),
                SignedMembershipProjection.canonicalBytes(shuffled));
    }

    @Test
    void tamperedBodyOrSignatureCannotBeAccepted() throws Exception {
        KeyPair issuer = key();
        byte[] bytes = SignedMembershipProjection.canonicalBytes(snapshot(7));
        byte[] signature = sign(bytes, issuer);
        byte[] altered = Arrays.copyOf(bytes, bytes.length);
        altered[10] ^= 1;
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verify(altered, signature,
                        issuer.getPublic(), START));
        signature[0] ^= 1;
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verify(bytes, signature,
                        issuer.getPublic(), START));
    }

    @Test
    void wrongOrUnpinnedSignerRejected() throws Exception {
        KeyPair issuer = key();
        KeyPair attacker = key();
        byte[] bytes = SignedMembershipProjection.canonicalBytes(snapshot(7));
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verify(bytes, sign(bytes, attacker),
                        issuer.getPublic(), START));
    }

    @Test
    void snapshotExpiryAndFutureStartAreFailClosed() throws Exception {
        KeyPair issuer = key();
        byte[] bytes = SignedMembershipProjection.canonicalBytes(snapshot(7));
        byte[] signature = sign(bytes, issuer);
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verify(bytes, signature,
                        issuer.getPublic(), START.minusSeconds(1)));
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verify(bytes, signature,
                        issuer.getPublic(), END));
    }

    @Test
    void signedButNoncanonicalRecordOrderDenied() throws Exception {
        KeyPair issuer = key();
        byte[] bytes = SignedMembershipProjection.canonicalBytes(snapshot(7));
        String source = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = source.split("\n");
        assertTrue(lines.length >= 5);
        String displaced = String.join("\n",
                lines[0], lines[1], lines[2], lines[4], lines[3]) + "\n";
        byte[] noncanonical = displaced.getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verify(noncanonical,
                        sign(noncanonical, issuer), issuer.getPublic(), START));
    }

    @Test
    void unexpectedRecordOrDuplicatePrincipalRejectedAfterSignature() throws Exception {
        KeyPair issuer = key();
        byte[] bytes = SignedMembershipProjection.canonicalBytes(snapshot(7));
        String source = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = source.split("\n");
        String duplicate = source + lines[3] + "\n";
        byte[] duplicateBytes = duplicate.getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verify(duplicateBytes,
                        sign(duplicateBytes, issuer), issuer.getPublic(), START));
        byte[] malformed = (source + "X\tunrecognized\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verify(malformed,
                        sign(malformed, issuer), issuer.getPublic(), START));
    }

    @Test
    void invalidUtf8AndMissingTerminationDeniedEvenIfSigned() throws Exception {
        KeyPair issuer = key();
        byte[] invalid = { 'V', 'I', 'P', (byte) 0xff, '\n' };
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verify(invalid, sign(invalid, issuer),
                        issuer.getPublic(), START));
        byte[] canonical = SignedMembershipProjection.canonicalBytes(snapshot(7));
        byte[] truncated = Arrays.copyOf(canonical, canonical.length - 1);
        assertThrows(SecurityException.class,
                () -> SignedMembershipProjection.verify(truncated,
                        sign(truncated, issuer), issuer.getPublic(), START));
    }

    @Test
    void revokedIdentityStillFailsWithValidSignature() throws Exception {
        KeyPair issuer = key();
        var revoked = new MembershipAdmissionPolicy(8, START, END,
                Map.of(HUMAN, new MembershipAdmissionPolicy.Grant(
                        Set.of("two_rivers"), START, END)), Set.of(WORKLOAD));
        byte[] bytes = SignedMembershipProjection.canonicalBytes(revoked);
        var result = SignedMembershipProjection.verify(
                bytes, sign(bytes, issuer), issuer.getPublic(), START);
        assertEquals(MembershipAdmissionPolicy.Decision.DENY,
                result.policy().decide(WORKLOAD, "bot_gym", START));
    }

    @Test
    void pureWatermarkRejectsRollbackAndEquivocation() throws Exception {
        KeyPair issuer = key();
        byte[] bytes = SignedMembershipProjection.canonicalBytes(snapshot(7));
        var verified = SignedMembershipProjection.verify(
                bytes, sign(bytes, issuer), issuer.getPublic(), START);
        var cursor = new SignedMembershipProjection.Watermark(
                verified.policy().revision(), verified.digest());
        assertEquals(cursor, cursor.advance(verified));
        var older = snapshot(6);
        byte[] olderBytes = SignedMembershipProjection.canonicalBytes(older);
        var oldVerified = SignedMembershipProjection.verify(
                olderBytes, sign(olderBytes, issuer), issuer.getPublic(), START);
        assertThrows(SecurityException.class, () -> cursor.advance(oldVerified));
        var changed = new MembershipAdmissionPolicy(7, START, END,
                Map.of(HUMAN, new MembershipAdmissionPolicy.Grant(
                        Set.of("two_rivers"), START, END)), Set.of(WORKLOAD));
        byte[] changedBytes = SignedMembershipProjection.canonicalBytes(changed);
        var changedVerified = SignedMembershipProjection.verify(
                changedBytes, sign(changedBytes, issuer), issuer.getPublic(), START);
        assertThrows(SecurityException.class, () -> cursor.advance(changedVerified));
        byte[] futureBytes = SignedMembershipProjection.canonicalBytes(snapshot(8));
        var advanced = SignedMembershipProjection.verify(
                futureBytes, sign(futureBytes, issuer), issuer.getPublic(), START);
        assertEquals(8L, cursor.advance(advanced).revision());
    }

    @Test
    void noWildcardServerAndNoUnsignedRevisionZero() {
        assertThrows(IllegalArgumentException.class,
                () -> SignedMembershipProjection.canonicalBytes(snapshot(0)));
        assertThrows(IllegalArgumentException.class,
                () -> new MembershipAdmissionPolicy.Grant(
                        Set.of("*"), START, END));
    }
}
