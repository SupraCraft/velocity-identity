package io.github.supracraft.velocityidentity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * U2b.2 research: an Ed25519-signed, independently pinned, short-lived
 * witness checkpoint for a bounded-staleness mode. This is NOT live-wired.
 *
 * A valid signature authenticates the checkpoint's issuer and contents,
 * NOT its claim to be the globally latest policy. A previously signed lease
 * can be replayed until it expires. Independent issuer/key custody, trusted
 * wall clock, bounded network partition and effective revocation policies
 * remain deployment admission gates.
 *
 * No private signing keys are held by VIP.
 */
public final class SignedMembershipWitnessLease {
    private static final String HEADER = "VIP-WITNESS-LEASE/1";
    private static final int MAX_BYTES = 2048;

    private SignedMembershipWitnessLease() {
    }

    public record Checkpoint(
            UUID realmId,
            UUID audienceRealmId,
            String membershipSignerSha256,
            long revision,
            String signedPolicyDigest,
            String witnessHeadSha256,
            Instant issuedAt,
            Instant expiresAt) {
        public Checkpoint {
            Objects.requireNonNull(realmId, "realmId");
            Objects.requireNonNull(audienceRealmId, "audienceRealmId");
            membershipSignerSha256 = validDigest(membershipSignerSha256);
            signedPolicyDigest = validDigest(signedPolicyDigest);
            witnessHeadSha256 = validDigest(witnessHeadSha256);
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (revision < 1 || !issuedAt.isBefore(expiresAt)) {
                throw new IllegalArgumentException("invalid witness revision or validity");
            }
        }
    }

    /**
     * A verification result cannot be directly constructed by application
     * callers. Does NOT carry proof that a claimed head is current.
     */
    public static final class Verified {
        private final Checkpoint checkpoint;
        private final String witnessKeyFingerprint;
        private final String envelopeDigest;

        private Verified(Checkpoint checkpoint, String witnessKeyFingerprint,
                         String envelopeDigest) {
            this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
            this.witnessKeyFingerprint = validDigest(witnessKeyFingerprint);
            this.envelopeDigest = validDigest(envelopeDigest);
        }

        public Checkpoint checkpoint() {
            return checkpoint;
        }

        public String witnessKeyFingerprint() {
            return witnessKeyFingerprint;
        }

        public String envelopeDigest() {
            return envelopeDigest;
        }

        /** An explicit comparison, not an externally authenticated head read. */
        public boolean describes(SignedMembershipProjection.RealmVerified membership) {
            Objects.requireNonNull(membership, "membership");
            return checkpoint.realmId().equals(membership.realmId())
                    && checkpoint.audienceRealmId().equals(membership.audienceRealmId())
                    && checkpoint.membershipSignerSha256().equals(
                            membership.signerFingerprint())
                    && checkpoint.revision() == membership.policy().revision()
                    && checkpoint.signedPolicyDigest().equals(membership.digest());
        }
    }

    /** Canonical ASCII bytes issued and signed only by a separate witness owner. */
    public static byte[] canonicalBytes(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        String canonical = HEADER + "\n"
                + "REALM\t" + checkpoint.realmId() + "\n"
                + "AUDIENCE\t" + checkpoint.audienceRealmId() + "\n"
                + "MEMBER_SIGNER_SHA256\t" + checkpoint.membershipSignerSha256() + "\n"
                + "REV\t" + checkpoint.revision() + "\n"
                + "POLICY_SHA256\t" + checkpoint.signedPolicyDigest() + "\n"
                + "HEAD_SHA256\t" + checkpoint.witnessHeadSha256() + "\n"
                + "ISSUED_AT\t" + checkpoint.issuedAt() + "\n"
                + "EXPIRES_AT\t" + checkpoint.expiresAt() + "\n";
        byte[] bytes = canonical.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("witness lease exceeds limit");
        }
        return bytes;
    }

    /**
     * Real Ed25519 verification against an independently pinned public key,
     * caller-pinned installation realm/audience, explicit time and maximum
     * lifetime. The caller MUST provide a trusted clock, never one read from
     * candidate data. This is bounded-staleness verification, not a
     * per-decision linearizable witness.
     */
    public static Verified verify(
            byte[] bytes,
            byte[] signature,
            PublicKey pinnedWitnessKey,
            UUID pinnedRealm,
            UUID pinnedAudience,
            Instant now,
            Duration maxLifetime) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(pinnedWitnessKey, "pinnedWitnessKey");
        Objects.requireNonNull(pinnedRealm, "pinnedRealm");
        Objects.requireNonNull(pinnedAudience, "pinnedAudience");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(maxLifetime, "maxLifetime");
        if (bytes.length == 0 || bytes.length > MAX_BYTES || signature.length != 64
                || maxLifetime.isZero() || maxLifetime.isNegative()) {
            throw new SecurityException("invalid witness envelope or duration");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pinnedWitnessKey);
            verifier.update(bytes);
            if (!verifier.verify(signature)) {
                throw new SecurityException("witness lease signature invalid");
            }
        } catch (GeneralSecurityException invalid) {
            throw new SecurityException("witness lease signature verification failed", invalid);
        }

        Checkpoint checkpoint = parseCanonical(bytes);
        if (!checkpoint.realmId().equals(pinnedRealm)
                || !checkpoint.audienceRealmId().equals(pinnedAudience)) {
            throw new SecurityException("witness realm or audience differs");
        }
        if (Duration.between(checkpoint.issuedAt(), checkpoint.expiresAt())
                .compareTo(maxLifetime) > 0
                || now.isBefore(checkpoint.issuedAt())
                || !now.isBefore(checkpoint.expiresAt())) {
            throw new SecurityException("witness lease outside trusted time window");
        }
        return new Verified(checkpoint,
                sha256(pinnedWitnessKey.getEncoded()), sha256(bytes));
    }

    private static Checkpoint parseCanonical(byte[] bytes) {
        for (byte b : bytes) {
            if (b < 0) {
                throw new SecurityException("witness lease must be ASCII");
            }
        }
        String[] lines = new String(bytes, StandardCharsets.US_ASCII)
                .split("\n", -1);
        if (lines.length != 10 || !HEADER.equals(lines[0])
                || !lines[9].isEmpty()) {
            throw new SecurityException("invalid witness lease structure");
        }
        try {
            Checkpoint checkpoint = new Checkpoint(
                    canonicalUuid(value(lines[1], "REALM")),
                    canonicalUuid(value(lines[2], "AUDIENCE")),
                    value(lines[3], "MEMBER_SIGNER_SHA256"),
                    canonicalRevision(value(lines[4], "REV")),
                    value(lines[5], "POLICY_SHA256"),
                    value(lines[6], "HEAD_SHA256"),
                    canonicalInstant(value(lines[7], "ISSUED_AT")),
                    canonicalInstant(value(lines[8], "EXPIRES_AT")));
            if (!MessageDigest.isEqual(bytes, canonicalBytes(checkpoint))) {
                throw new SecurityException("noncanonical witness lease");
            }
            return checkpoint;
        } catch (RuntimeException invalid) {
            throw new SecurityException("invalid witness lease fields", invalid);
        }
    }

    private static String value(String line, String key) {
        String prefix = key + "\t";
        if (!line.startsWith(prefix) || line.length() <= prefix.length()) {
            throw new SecurityException("missing required witness field " + key);
        }
        return line.substring(prefix.length());
    }

    private static UUID canonicalUuid(String raw) {
        UUID id = UUID.fromString(raw);
        if (!id.toString().equals(raw)) {
            throw new SecurityException("noncanonical UUID");
        }
        return id;
    }

    private static long canonicalRevision(String raw) {
        if (!raw.matches("[1-9][0-9]{0,17}")) {
            throw new SecurityException("noncanonical witness revision");
        }
        return Long.parseLong(raw);
    }

    private static Instant canonicalInstant(String raw) {
        Instant instant = Instant.parse(raw);
        if (!instant.toString().equals(raw)) {
            throw new SecurityException("noncanonical witness time");
        }
        return instant;
    }

    private static String validDigest(String hex) {
        if (hex == null || !hex.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid SHA256 digest");
        }
        return hex;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
