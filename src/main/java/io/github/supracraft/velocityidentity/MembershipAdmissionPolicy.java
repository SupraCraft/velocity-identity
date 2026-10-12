package io.github.supracraft.velocityidentity;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, pure evaluation of a separately issued membership projection.
 *
 * This is an opt-in U1 consumer primitive, NOT a VIP runtime configuration
 * loader. No file, network, login, or authority state is changed here.
 * Snapshot authentication, anti-rollback, and session invalidation are U2
 * gates and MUST be qualified before wiring this into live routing.
 */
public final class MembershipAdmissionPolicy {
    public enum Decision { ALLOW, DENY }

    public record Grant(Set<String> servers, Instant validFrom, Instant expiresAt) {
        public Grant {
            Objects.requireNonNull(servers, "servers");
            validFrom = Objects.requireNonNull(validFrom, "validFrom");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (!validFrom.isBefore(expiresAt)) {
                throw new IllegalArgumentException("invalid grant validity window");
            }
            Set<String> normalized = new HashSet<>();
            for (String server : servers) {
                normalized.add(normalizeServer(server));
            }
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("a grant needs an explicit server");
            }
            servers = Collections.unmodifiableSet(normalized);
        }

        public boolean validAt(Instant now) {
            return !now.isBefore(validFrom) && now.isBefore(expiresAt);
        }
    }

    private final long revision;
    private final Instant validFrom;
    private final Instant expiresAt;
    private final Map<CanonicalPrincipal, Grant> grants;
    private final Set<CanonicalPrincipal> revoked;

    public MembershipAdmissionPolicy(
            long revision,
            Instant validFrom,
            Instant expiresAt,
            Map<CanonicalPrincipal, Grant> grants,
            Set<CanonicalPrincipal> revoked) {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!validFrom.isBefore(expiresAt)) {
            throw new IllegalArgumentException("invalid snapshot validity window");
        }
        Objects.requireNonNull(grants, "grants");
        Objects.requireNonNull(revoked, "revoked");
        Map<CanonicalPrincipal, Grant> copy = new HashMap<>();
        grants.forEach((principal, grant) ->
                copy.put(Objects.requireNonNull(principal, "principal"),
                        Objects.requireNonNull(grant, "grant")));
        Set<CanonicalPrincipal> revokedCopy = new HashSet<>();
        revoked.forEach(principal -> revokedCopy.add(
                Objects.requireNonNull(principal, "revoked principal")));
        if (!Collections.disjoint(copy.keySet(), revokedCopy)) {
            throw new IllegalArgumentException("grants conflict with revocations");
        }
        this.revision = revision;
        this.grants = Collections.unmodifiableMap(copy);
        this.revoked = Collections.unmodifiableSet(revokedCopy);
    }

    public long revision() {
        return revision;
    }

    public Decision decide(
            CanonicalPrincipal principal, String server, Instant now) {
        Objects.requireNonNull(now, "now");
        if (principal == null || server == null
                || now.isBefore(validFrom) || !now.isBefore(expiresAt)
                || revoked.contains(principal)) {
            return Decision.DENY;
        }
        Grant grant = grants.get(principal);
        if (grant == null || !grant.validAt(now)) {
            return Decision.DENY;
        }
        String normalized;
        try {
            normalized = normalizeServer(server);
        } catch (IllegalArgumentException error) {
            return Decision.DENY;
        }
        return grant.servers().contains(normalized)
                ? Decision.ALLOW : Decision.DENY;
    }

    private static String normalizeServer(String server) {
        String normalized = Objects.requireNonNull(server, "server")
                .trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("invalid explicit server name");
        }
        return normalized;
    }
}
