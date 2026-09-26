package io.github.supracraft.velocityidentity;

import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record ProviderDescriptor(
        String id,
        String issuer,
        int priority,
        Set<AdmissionClass> admissionClasses,
        Set<String> mechanisms,
        Set<ProviderCapability> capabilities) {

    public ProviderDescriptor {
        id = requireNonBlank(id, "id");
        issuer = requireNonBlank(issuer, "issuer");
        admissionClasses = Collections.unmodifiableSet(
                new TreeSet<>(Objects.requireNonNull(admissionClasses, "admissionClasses")));
        TreeSet<String> normalizedMechanisms = new TreeSet<>();
        for (String mechanism : Objects.requireNonNull(mechanisms, "mechanisms")) {
            normalizedMechanisms.add(normalizeMechanism(mechanism));
        }
        mechanisms = Collections.unmodifiableSet(normalizedMechanisms);
        capabilities = Collections.unmodifiableSet(
                new TreeSet<>(Objects.requireNonNull(capabilities, "capabilities")));
        if (admissionClasses.isEmpty()) {
            throw new IllegalArgumentException("admissionClasses must not be empty");
        }
        if (mechanisms.isEmpty()) {
            throw new IllegalArgumentException("mechanisms must not be empty");
        }
    }

    public boolean supports(AdmissionClass admissionClass, String mechanism) {
        return admissionClasses.contains(Objects.requireNonNull(admissionClass, "admissionClass"))
                && mechanisms.contains(normalizeMechanism(mechanism));
    }

    static String normalizeMechanism(String mechanism) {
        return requireNonBlank(mechanism, "mechanism").toLowerCase(Locale.ROOT);
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
