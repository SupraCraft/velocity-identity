package io.github.supracraft.velocityidentity;

import java.util.Objects;

public record CanonicalPrincipal(String issuer, String subject, PrincipalKind kind) {
    public CanonicalPrincipal {
        issuer = requireNonBlank(issuer, "issuer");
        subject = requireNonBlank(subject, "subject");
        kind = Objects.requireNonNull(kind, "kind");
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
