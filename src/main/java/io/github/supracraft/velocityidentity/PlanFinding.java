package io.github.supracraft.velocityidentity;

import java.util.Objects;

public record PlanFinding(Severity severity, String code, String detail) implements Comparable<PlanFinding> {
    public PlanFinding {
        severity = Objects.requireNonNull(severity, "severity");
        code = require(code, "code");
        detail = require(detail, "detail");
    }

    @Override
    public int compareTo(PlanFinding other) {
        int severityOrder = severity.compareTo(other.severity);
        if (severityOrder != 0) {
            return severityOrder;
        }
        int codeOrder = code.compareTo(other.code);
        return codeOrder != 0 ? codeOrder : detail.compareTo(other.detail);
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public enum Severity {
        INFO,
        WARNING,
        BLOCKER
    }
}
