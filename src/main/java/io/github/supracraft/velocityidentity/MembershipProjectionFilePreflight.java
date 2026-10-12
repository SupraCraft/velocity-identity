package io.github.supracraft.velocityidentity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Objects;

/**
 * Operator-facing read-only sensor for separately supplied signed membership
 * artifacts. Two independent file reads must produce identical plans and
 * observations. No runtime publication, filesystem writes, key generation,
 * private key handling, or trusted-watermark persistence occurs here.
 *
 * The caller MUST obtain trustedWatermark from a separately protected
 * rollback-resistant authority. Providing a forged local cursor defeats the
 * pure rollback test; the result MUST NOT authorize player connections.
 */
public final class MembershipProjectionFilePreflight {
    private MembershipProjectionFilePreflight() {
    }

    public record Evidence(
            MembershipProjectionPreflight.Plan firstPlan,
            MembershipProjectionPreflight.Plan secondPlan,
            MembershipProjectionPreflight.Observation independentObservation) {
        public Evidence {
            Objects.requireNonNull(firstPlan, "firstPlan");
            Objects.requireNonNull(secondPlan, "secondPlan");
            Objects.requireNonNull(independentObservation, "independentObservation");
        }

        public boolean twoIdenticalReadyPlans() {
            return firstPlan.equals(secondPlan)
                    && firstPlan.isReadyForReconciliation()
                    && independentObservation.fingerprint().equals(
                            firstPlan.observationFingerprint());
        }
    }

    public static Evidence inspect(
            Path projection,
            Path detachedSignature,
            Path pinnedPublicKeyDer,
            SignedMembershipProjection.Watermark trustedWatermark,
            Instant now) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(now, "now");
        Inputs first = read(projection, detachedSignature, pinnedPublicKeyDer);
        var firstPlan = MembershipProjectionPreflight.plan(
                first.payload(), first.signature(), first.issuer(),
                trustedWatermark, now);

        Inputs independent = read(projection, detachedSignature, pinnedPublicKeyDer);
        var secondPlan = MembershipProjectionPreflight.plan(
                independent.payload(), independent.signature(),
                independent.issuer(), trustedWatermark, now);
        var observation = MembershipProjectionPreflight.observe(
                independent.payload(), independent.signature(),
                independent.issuer(), trustedWatermark);

        return new Evidence(firstPlan, secondPlan, observation);
    }

    private record Inputs(byte[] payload, byte[] signature, PublicKey issuer) {
    }

    private static Inputs read(Path payload, Path signature, Path publicKey)
            throws IOException, GeneralSecurityException {
        byte[] payloadBytes = boundedRead(payload, 262_144);
        byte[] signatureBytes = boundedRead(signature, 64);
        byte[] keyBytes = boundedRead(publicKey, 128);
        if (signatureBytes.length != 64 || keyBytes.length == 0) {
            throw new SecurityException("malformed signature or public key");
        }
        PublicKey issuer = KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
        return new Inputs(payloadBytes, signatureBytes, issuer);
    }

    /**
     * No symbolic links; a bounded read stops after maxBytes+1. A caller that
     * needs stronger path-race resistance must provide a sealed file handle.
     */
    private static byte[] boundedRead(Path path, int maxBytes) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("expected a regular non-symlink input file");
        }
        try (InputStream input = Files.newInputStream(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = input.readNBytes(maxBytes + 1);
            if (data.length > maxBytes) {
                throw new SecurityException("oversized membership preflight input");
            }
            return data;
        }
    }
}
