package io.github.supracraft.velocityidentity;

import java.util.Objects;

public record ApplyReceipt(
        ApplyStatus status,
        String planFingerprint,
        String observationFingerprint,
        AdmissionPolicy beforePolicy,
        AdmissionPolicy afterPolicy,
        String detail) {

    public ApplyReceipt {
        status = Objects.requireNonNull(status, "status");
        planFingerprint = Objects.requireNonNull(planFingerprint, "planFingerprint");
        observationFingerprint = Objects.requireNonNull(observationFingerprint, "observationFingerprint");
        beforePolicy = Objects.requireNonNull(beforePolicy, "beforePolicy");
        afterPolicy = Objects.requireNonNull(afterPolicy, "afterPolicy");
        detail = Objects.requireNonNull(detail, "detail");
    }

    public enum ApplyStatus {
        APPLIED,
        NOOP,
        BLOCKED,
        STALE,
        ROLLED_BACK
    }
}
