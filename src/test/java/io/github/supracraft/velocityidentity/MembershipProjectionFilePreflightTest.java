package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MembershipProjectionFilePreflightTest {
    @TempDir
    Path temp;

    private static final Instant START = Instant.parse("2026-10-12T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-13T00:00:00Z");
    private static final Instant NOW = START.plusSeconds(5);

    private static MembershipAdmissionPolicy policy(long revision) {
        var human = new CanonicalPrincipal(
                "https://sessionserver.mojang.com", "verified-player-uuid",
                PrincipalKind.HUMAN);
        var workload = new CanonicalPrincipal(
                "urn:supracraft:vip:workload", "agents/builder-01",
                PrincipalKind.WORKLOAD);
        return new MembershipAdmissionPolicy(revision, START, END,
                Map.of(
                        human, new MembershipAdmissionPolicy.Grant(
                                Set.of("two_rivers"), START, END),
                        workload, new MembershipAdmissionPolicy.Grant(
                                Set.of("bot_gym"), START, END)),
                Set.of());
    }

    private static byte[] signed(KeyPair issuer, byte[] bytes) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(issuer.getPrivate());
        signer.update(bytes);
        return signer.sign();
    }

    private static SignedMembershipProjection.Watermark cursor(
            KeyPair issuer, MembershipAdmissionPolicy value) throws Exception {
        byte[] canonical = SignedMembershipProjection.canonicalBytes(value);
        var verified = SignedMembershipProjection.verify(
                canonical, signed(issuer, canonical), issuer.getPublic(), NOW);
        return new SignedMembershipProjection.Watermark(
                verified.policy().revision(),
                verified.digest(), verified.signerFingerprint());
    }

    private record Artifacts(Path projection, Path signature, Path publicKey,
                             byte[] expectedPayload, byte[] expectedSignature) {
    }

    private Artifacts writeArtifacts(KeyPair issuer, MembershipAdmissionPolicy value)
            throws Exception {
        byte[] payload = SignedMembershipProjection.canonicalBytes(value);
        byte[] signature = signed(issuer, payload);
        Path body = temp.resolve("membership.policy");
        Path detached = temp.resolve("membership.sig");
        Path key = temp.resolve("issuer.der");
        Files.write(body, payload);
        Files.write(detached, signature);
        Files.write(key, issuer.getPublic().getEncoded());
        return new Artifacts(body, detached, key, payload, signature);
    }

    @Test
    void exactRealFileInputsYieldTwoIdenticalReadyPlansAndIndependentObservation()
            throws Exception {
        KeyPair issuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Artifacts artifacts = writeArtifacts(issuer, policy(2));
        var trusted = cursor(issuer, policy(1));
        long lastModified = Files.getLastModifiedTime(
                artifacts.projection()).toMillis();

        var evidence = MembershipProjectionFilePreflight.inspect(
                artifacts.projection(), artifacts.signature(),
                artifacts.publicKey(), trusted, NOW);

        assertTrue(evidence.twoIdenticalReadyPlans());
        assertEquals(evidence.firstPlan(), evidence.secondPlan());
        assertEquals(evidence.firstPlan().observationFingerprint(),
                evidence.independentObservation().fingerprint());
        assertEquals(2L, evidence.firstPlan().proposedRevision());
        assertEquals(1L, trusted.revision());
        assertArrayEquals(artifacts.expectedPayload(),
                Files.readAllBytes(artifacts.projection()));
        assertArrayEquals(artifacts.expectedSignature(),
                Files.readAllBytes(artifacts.signature()));
        assertEquals(lastModified,
                Files.getLastModifiedTime(artifacts.projection()).toMillis());
    }

    @Test
    void expiredAndRollbackInputsCanProduceOnlyBlockedPlans()
            throws Exception {
        KeyPair issuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Artifacts artifacts = writeArtifacts(issuer, policy(2));
        var newer = cursor(issuer, policy(3));
        var rolledBack = MembershipProjectionFilePreflight.inspect(
                artifacts.projection(), artifacts.signature(),
                artifacts.publicKey(), newer, NOW);
        assertFalse(rolledBack.twoIdenticalReadyPlans());
        assertEquals(MembershipProjectionPreflight.Finding.WATERMARK_REJECTED,
                rolledBack.firstPlan().finding());

        var expired = MembershipProjectionFilePreflight.inspect(
                artifacts.projection(), artifacts.signature(),
                artifacts.publicKey(), cursor(issuer, policy(1)), END);
        assertEquals(MembershipProjectionPreflight.Finding.CANDIDATE_REJECTED,
                expired.firstPlan().finding());
    }

    @Test
    void missingProtectedWatermarkNeverBootstrapsItself() throws Exception {
        KeyPair issuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Artifacts artifacts = writeArtifacts(issuer, policy(2));
        var evidence = MembershipProjectionFilePreflight.inspect(
                artifacts.projection(), artifacts.signature(),
                artifacts.publicKey(), null, NOW);
        assertFalse(evidence.twoIdenticalReadyPlans());
        assertEquals(MembershipProjectionPreflight.Finding.MISSING_TRUSTED_INPUT,
                evidence.firstPlan().finding());
    }

    @Test
    void tamperedInputsAreBlockedAndOversizeOrSymlinkInputsRejected()
            throws Exception {
        KeyPair issuer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Artifacts artifacts = writeArtifacts(issuer, policy(2));
        var trusted = cursor(issuer, policy(1));
        byte[] forged = Arrays.copyOf(artifacts.expectedPayload(),
                artifacts.expectedPayload().length);
        forged[6] ^= 1;
        Files.write(artifacts.projection(), forged);
        var denied = MembershipProjectionFilePreflight.inspect(
                artifacts.projection(), artifacts.signature(),
                artifacts.publicKey(), trusted, NOW);
        assertEquals(MembershipProjectionPreflight.Finding.CANDIDATE_REJECTED,
                denied.firstPlan().finding());

        Files.write(artifacts.projection(), new byte[262_145]);
        assertThrows(SecurityException.class,
                () -> MembershipProjectionFilePreflight.inspect(
                        artifacts.projection(), artifacts.signature(),
                        artifacts.publicKey(), trusted, NOW));

        Files.write(artifacts.projection(), artifacts.expectedPayload());
        Path symlink = temp.resolve("spoofed.sig");
        Files.createSymbolicLink(symlink, artifacts.signature());
        assertThrows(java.io.IOException.class,
                () -> MembershipProjectionFilePreflight.inspect(
                        artifacts.projection(), symlink,
                        artifacts.publicKey(), trusted, NOW));
    }
}
