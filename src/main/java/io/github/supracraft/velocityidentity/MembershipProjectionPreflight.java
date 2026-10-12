package io.github.supracraft.velocityidentity;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;

/**
 * U2b.1: read-only, deterministic source-qualification plan and an independent
 * input observation. This class does not load files, persist an anti-rollback
 * watermark, touch VIP routing, or mutate a Minecraft session.
 *
 * READY means only that externally supplied evidence passes preflight. It is
 * NOT runtime admission readiness. The trusted watermark must come from an
 * independently authenticated and rollback-resistant authority in U2b.2.
 */
public final class MembershipProjectionPreflight {
    private MembershipProjectionPreflight() {
    }

    public enum Result {
        READY_FOR_RECONCILIATION,
        BLOCKED
    }

    public enum Finding {
        NONE,
        MISSING_TRUSTED_INPUT,
        CANDIDATE_REJECTED,
        WATERMARK_REJECTED
    }

    public record Observation(
            String candidateSha256,
            String signatureSha256,
            String signerSha256,
            long watermarkRevision,
            String watermarkSha256,
            String fingerprint) {
        public Observation {
            Objects.requireNonNull(candidateSha256, "candidateSha256");
            Objects.requireNonNull(signatureSha256, "signatureSha256");
            Objects.requireNonNull(signerSha256, "signerSha256");
            Objects.requireNonNull(watermarkSha256, "watermarkSha256");
            Objects.requireNonNull(fingerprint, "fingerprint");
        }
    }

    public record Plan(Result result,
                       Finding finding,
                       String observationFingerprint,
                       long proposedRevision,
                       String proposedDigest,
                       String planFingerprint) {
        public Plan {
            result = Objects.requireNonNull(result, "result");
            finding = Objects.requireNonNull(finding, "finding");
            Objects.requireNonNull(observationFingerprint, "observationFingerprint");
            Objects.requireNonNull(proposedDigest, "proposedDigest");
            Objects.requireNonNull(planFingerprint, "planFingerprint");
            if (result == Result.READY_FOR_RECONCILIATION
                    && finding != Finding.NONE) {
                throw new IllegalArgumentException("ready plan cannot carry blockers");
            }
        }

        public boolean isReadyForReconciliation() {
            return result == Result.READY_FOR_RECONCILIATION;
        }
    }

    /**
     * Independent observation from candidate bytes and externally pinned
     * trust inputs. An absent value is represented explicitly and causes the
     * separately computed plan to BLOCK, rather than silently bootstrapping.
     */
    public static Observation observe(
            byte[] candidate,
            byte[] signature,
            PublicKey pinnedIssuer,
            SignedMembershipProjection.Watermark trustedWatermark) {
        String candidateHash = hashNullable(candidate);
        String signatureHash = hashNullable(signature);
        String signerHash = pinnedIssuer == null
                ? "missing" : hashNullable(pinnedIssuer.getEncoded());
        long revision = trustedWatermark == null
                ? -1 : trustedWatermark.revision();
        String watermarkDigest = trustedWatermark == null
                ? "missing" : trustedWatermark.digest();
        String canonical = candidateHash + "\n" + signatureHash + "\n"
                + signerHash + "\n" + revision + "\n" + watermarkDigest
                + "\n" + (trustedWatermark == null
                        ? "missing" : trustedWatermark.signerFingerprint()) + "\n";
        return new Observation(candidateHash, signatureHash, signerHash,
                revision, watermarkDigest, Digests.sha256(canonical));
    }

    /**
     * Pure plan. No policy is applied and no watermark is written. Time is an
     * explicit input so repeated plans for identical evidence are byte-stable.
     */
    public static Plan plan(
            byte[] candidate,
            byte[] signature,
            PublicKey pinnedIssuer,
            SignedMembershipProjection.Watermark trustedWatermark,
            Instant now) {
        Objects.requireNonNull(now, "now");
        Observation observed = observe(
                candidate, signature, pinnedIssuer, trustedWatermark);
        if (candidate == null || signature == null || pinnedIssuer == null
                || trustedWatermark == null) {
            return finish(Result.BLOCKED, Finding.MISSING_TRUSTED_INPUT,
                    observed, -1, "");
        }
        final SignedMembershipProjection.Verified verified;
        try {
            verified = SignedMembershipProjection.verify(
                    candidate, signature, pinnedIssuer, now);
        } catch (RuntimeException invalid) {
            return finish(Result.BLOCKED, Finding.CANDIDATE_REJECTED,
                    observed, -1, "");
        }
        try {
            trustedWatermark.advance(verified);
        } catch (RuntimeException rejected) {
            return finish(Result.BLOCKED, Finding.WATERMARK_REJECTED,
                    observed, verified.policy().revision(), verified.digest());
        }
        return finish(Result.READY_FOR_RECONCILIATION, Finding.NONE,
                observed, verified.policy().revision(), verified.digest());
    }

    private static Plan finish(Result result, Finding finding,
                               Observation observed, long revision,
                               String digest) {
        String canonical = "VIP-PREFLIGHT/1\n" + observed.fingerprint()
                + "\n" + result + "\n" + finding + "\n"
                + revision + "\n" + digest + "\n";
        return new Plan(result, finding, observed.fingerprint(),
                revision, digest, Digests.sha256(canonical));
    }

    private static String hashNullable(byte[] bytes) {
        if (bytes == null) {
            return "missing";
        }
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
