package io.github.supracraft.velocityidentity;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * U2a: detached Ed25519 verification of an exact canonical membership
 * projection. The signer is externally pinned by its trusted issuer.
 *
 * This class is deliberately not wired to VIP runtime. U2b must implement a
 * crash-safe, protected high-watermark, reconciled activation, and session
 * invalidation before these policies may authorize live Minecraft sessions.
 */
public final class SignedMembershipProjection {
    private static final String HEADER = "VIP-MEMBERSHIP/1";
    private static final int MAX_BYTES = 262_144;
    private static final int MAX_ENTRIES = 2_000;

    private SignedMembershipProjection() {
    }

    /** Evidence-bearing value created only by successful signature verification. */
    public static final class Verified {
        private final MembershipAdmissionPolicy policy;
        private final String digest;
        private final String signerFingerprint;

        private Verified(MembershipAdmissionPolicy policy,
                         String digest, String signerFingerprint) {
            this.policy = Objects.requireNonNull(policy, "policy");
            this.digest = validateHash(digest);
            this.signerFingerprint = validateHash(signerFingerprint);
        }

        public MembershipAdmissionPolicy policy() {
            return policy;
        }

        public String digest() {
            return digest;
        }

        public String signerFingerprint() {
            return signerFingerprint;
        }
    }

    /**
     * A pure anti-rollback decision, *not* persistent state.
     * A caller must restore an authenticated, durable cursor before invoking
     * this method; it cannot bootstrap itself from an absent cursor.
     */
    public record Watermark(long revision, String digest) {
        public Watermark {
            if (revision < 1) {
                throw new IllegalArgumentException("nonpositive watermark revision");
            }
            digest = validateHash(digest);
        }

        public Watermark advance(Verified next) {
            Objects.requireNonNull(next, "next");
            long nextRevision = next.policy().revision();
            if (nextRevision < revision) {
                throw new SecurityException("membership snapshot rollback");
            }
            if (nextRevision == revision && !digest.equals(next.digest())) {
                throw new SecurityException("membership revision equivocation");
            }
            return nextRevision > revision
                    ? new Watermark(nextRevision, next.digest())
                    : this;
        }
    }

    /**
     * Produce deterministic UTF-8 bytes for an independently owned issuer.
     * This API does not perform signing or store issuer private keys.
     */
    public static byte[] canonicalBytes(MembershipAdmissionPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (policy.revision() < 1) {
            throw new IllegalArgumentException("signed projection needs positive revision");
        }
        StringBuilder builder = new StringBuilder(HEADER).append('\n')
                .append("REV\t").append(policy.revision()).append('\n')
                .append("VALID\t").append(policy.validFrom()).append('\t')
                .append(policy.expiresAt()).append('\n');
        TreeSet<String> grantLines = new TreeSet<>();
        policy.grants().forEach((principal, grant) -> {
            TreeSet<String> sortedServers = new TreeSet<>(grant.servers());
            if (sortedServers.isEmpty()) {
                throw new IllegalArgumentException("empty grant");
            }
            grantLines.add("G\t" + principalColumns(principal) + "\t"
                    + String.join(",", sortedServers) + "\t"
                    + grant.validFrom() + "\t" + grant.expiresAt());
        });
        TreeSet<String> revocationLines = new TreeSet<>();
        policy.revoked().forEach(principal ->
                revocationLines.add("R\t" + principalColumns(principal)));
        if (grantLines.size() + revocationLines.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("too many membership entries");
        }
        grantLines.forEach(line -> builder.append(line).append('\n'));
        revocationLines.forEach(line -> builder.append(line).append('\n'));
        byte[] encoded = builder.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) {
            throw new IllegalArgumentException("membership projection exceeds budget");
        }
        return encoded;
    }

    /**
     * A verified issuer public key must be independently pinned, never read
     * from the same candidate snapshot or its untrusted delivery metadata.
     */
    public static Verified verify(byte[] candidate,
                                  byte[] detachedSignature,
                                  PublicKey pinnedIssuerKey,
                                  Instant now) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(detachedSignature, "detachedSignature");
        Objects.requireNonNull(pinnedIssuerKey, "pinnedIssuerKey");
        Objects.requireNonNull(now, "now");
        if (candidate.length == 0 || candidate.length > MAX_BYTES
                || detachedSignature.length != 64) {
            throw new SecurityException("invalid membership envelope size");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pinnedIssuerKey);
            verifier.update(candidate);
            if (!verifier.verify(detachedSignature)) {
                throw new SecurityException("membership signature invalid");
            }
        } catch (GeneralSecurityException invalid) {
            throw new SecurityException("membership signature verification failed", invalid);
        }

        MembershipAdmissionPolicy policy = parseCanonical(candidate);
        if (now.isBefore(policy.validFrom()) || !now.isBefore(policy.expiresAt())) {
            throw new SecurityException("membership snapshot outside validity window");
        }
        return new Verified(policy, hash(candidate), hash(pinnedIssuerKey.getEncoded()));
    }

    private static MembershipAdmissionPolicy parseCanonical(byte[] bytes) {
        String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new SecurityException("invalid UTF-8 in membership snapshot", malformed);
        }
        String[] lines = source.split("\n", -1);
        if (lines.length < 4 || !lines[lines.length - 1].isEmpty()
                || !HEADER.equals(lines[0])) {
            throw new SecurityException("invalid membership header or termination");
        }
        String[] revision = lines[1].split("\t", -1);
        String[] validity = lines[2].split("\t", -1);
        if (revision.length != 2 || !"REV".equals(revision[0])
                || validity.length != 3 || !"VALID".equals(validity[0])) {
            throw new SecurityException("invalid membership metadata");
        }
        long value = strictRevision(revision[1]);
        Instant start = strictInstant(validity[1]);
        Instant end = strictInstant(validity[2]);
        Map<CanonicalPrincipal, MembershipAdmissionPolicy.Grant> grants = new HashMap<>();
        Set<CanonicalPrincipal> revoked = new HashSet<>();
        if (lines.length - 4 > MAX_ENTRIES) {
            throw new SecurityException("too many membership entries");
        }
        for (int i = 3; i < lines.length - 1; i++) {
            String[] fields = lines[i].split("\t", -1);
            if (fields.length == 7 && "G".equals(fields[0])) {
                CanonicalPrincipal principal = principal(fields);
                String[] parts = fields[4].split(",", -1);
                Set<String> servers = new HashSet<>(Arrays.asList(parts));
                if (parts.length != servers.size() || parts.length == 0) {
                    throw new SecurityException("duplicate or missing backend name");
                }
                MembershipAdmissionPolicy.Grant grant =
                        new MembershipAdmissionPolicy.Grant(
                                servers, strictInstant(fields[5]), strictInstant(fields[6]));
                if (grant.validFrom().isBefore(start) || grant.expiresAt().isAfter(end)
                        || grants.putIfAbsent(principal, grant) != null) {
                    throw new SecurityException("invalid or repeated principal grant");
                }
            } else if (fields.length == 4 && "R".equals(fields[0])) {
                CanonicalPrincipal principal = principal(fields);
                if (!revoked.add(principal)) {
                    throw new SecurityException("duplicate revoked principal");
                }
            } else {
                throw new SecurityException("unknown membership record");
            }
        }
        MembershipAdmissionPolicy policy;
        try {
            policy = new MembershipAdmissionPolicy(value, start, end, grants, revoked);
        } catch (IllegalArgumentException invalid) {
            throw new SecurityException("invalid membership content", invalid);
        }
        if (!MessageDigest.isEqual(bytes, canonicalBytes(policy))) {
            throw new SecurityException("noncanonical membership projection");
        }
        return policy;
    }

    private static CanonicalPrincipal principal(String[] fields) {
        try {
            return new CanonicalPrincipal(
                    decode(fields[1]),
                    decode(fields[2]),
                    PrincipalKind.valueOf(fields[3]));
        } catch (RuntimeException invalid) {
            throw new SecurityException("invalid canonical principal", invalid);
        }
    }

    private static String principalColumns(CanonicalPrincipal principal) {
        Objects.requireNonNull(principal, "principal");
        return encode(principal.issuer()) + "\t" + encode(principal.subject())
                + "\t" + principal.kind();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        try {
            if (!value.matches("[A-Za-z0-9_-]{2,2048}")) {
                throw new IllegalArgumentException("noncanonical base64url field");
            }
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (!encode(decoded).equals(value)) {
                throw new IllegalArgumentException("noncanonical encoded principal");
            }
            return decoded;
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw new SecurityException("invalid encoded principal", invalid);
        }
    }

    private static long strictRevision(String value) {
        if (!value.matches("[1-9][0-9]{0,17}")) {
            throw new SecurityException("noncanonical revision");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new SecurityException("revision outside range", invalid);
        }
    }

    private static Instant strictInstant(String value) {
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.toString().equals(value)) {
                throw new SecurityException("noncanonical time");
            }
            return parsed;
        } catch (RuntimeException invalid) {
            throw new SecurityException("invalid time", invalid);
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static String validateHash(String value) {
        Objects.requireNonNull(value, "sha256");
        if (!value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid sha256 digest");
        }
        return value;
    }
}
