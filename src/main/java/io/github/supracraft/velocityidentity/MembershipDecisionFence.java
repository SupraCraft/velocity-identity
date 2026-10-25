package io.github.supracraft.velocityidentity;

import java.time.Instant;
import java.util.UUID;

/**
 * Pure fail-closed membership decision contract, deliberately NOT wired into
 * Velocity login/transfer events. A RealmWatermark is a plain value, not proof
 * of external witness authenticity. A future runtime must supply this value
 * from a separately protected and independently verified authority on every
 * strong-consistency decision; otherwise membership must remain disabled.
 *
 * NOT_ENABLED means "defer to existing VIP admission rules", not a permit.
 * A positive result never supersedes host/backend profile restrictions.
 */
public final class MembershipDecisionFence {
    private MembershipDecisionFence() {
    }

    public enum Verdict {
        NOT_ENABLED,
        ALLOW,
        DENY_MISSING_AUTHORITY,
        DENY_WITNESS_OR_CLOCK,
        DENY_STALE_GENERATION,
        DENY_REALM_OR_REVISION,
        DENY_EXPIRED_OR_UNGRANTED
    }

    /**
     * Pure predicate for both initial backend admission and subsequent route
     * transitions. No network call, trust pin loading, session mutation,
     * temporal scheduling or storage is performed here.
     */
    public static Verdict decide(
            boolean membershipEnabled,
            SignedMembershipProjection.RealmVerified signatureVerified,
            SignedMembershipProjection.RealmWatermark externalTrustedCursor,
            UUID pinnedRealm,
            UUID pinnedAudience,
            CanonicalPrincipal principal,
            String backendName,
            Instant observedNow,
            boolean witnessCurrentAndReachable,
            boolean trustedClock,
            long capturedGeneration,
            long effectiveGeneration) {
        if (!membershipEnabled) {
            return Verdict.NOT_ENABLED;
        }
        if (signatureVerified == null || externalTrustedCursor == null
                || pinnedRealm == null || pinnedAudience == null
                || observedNow == null || principal == null || backendName == null) {
            return Verdict.DENY_MISSING_AUTHORITY;
        }
        if (!witnessCurrentAndReachable || !trustedClock) {
            return Verdict.DENY_WITNESS_OR_CLOCK;
        }
        if (capturedGeneration < 0 || effectiveGeneration < 0
                || capturedGeneration != effectiveGeneration) {
            return Verdict.DENY_STALE_GENERATION;
        }
        var cursor = externalTrustedCursor.cursor();
        var policy = signatureVerified.policy();
        if (!pinnedRealm.equals(signatureVerified.realmId())
                || !pinnedAudience.equals(signatureVerified.audienceRealmId())
                || !pinnedRealm.equals(externalTrustedCursor.realmId())
                || !pinnedAudience.equals(externalTrustedCursor.audienceRealmId())
                || cursor.revision() != policy.revision()
                || !cursor.digest().equals(signatureVerified.digest())
                || !cursor.signerFingerprint().equals(
                        signatureVerified.signerFingerprint())) {
            return Verdict.DENY_REALM_OR_REVISION;
        }
        return policy.decide(principal, backendName, observedNow)
                == MembershipAdmissionPolicy.Decision.ALLOW
                ? Verdict.ALLOW : Verdict.DENY_EXPIRED_OR_UNGRANTED;
    }
}
