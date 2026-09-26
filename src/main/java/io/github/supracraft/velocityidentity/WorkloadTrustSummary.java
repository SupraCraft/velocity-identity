package io.github.supracraft.velocityidentity;

import java.util.List;
import java.util.Objects;

public record WorkloadTrustSummary(
        String issuer,
        int bindingCount,
        List<String> keyIds,
        String fingerprint) {

    public WorkloadTrustSummary {
        issuer = Objects.requireNonNull(issuer, "issuer");
        if (bindingCount < 0) {
            throw new IllegalArgumentException(
                    "bindingCount must not be negative");
        }
        keyIds = List.copyOf(Objects.requireNonNull(keyIds, "keyIds"));
        fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
