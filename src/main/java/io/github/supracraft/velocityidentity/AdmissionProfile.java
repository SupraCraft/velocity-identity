package io.github.supracraft.velocityidentity;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record AdmissionProfile(String id, AdmissionClass admissionClass, Set<String> allowedServers) {
    public AdmissionProfile {
        id = Objects.requireNonNull(id, "id").trim();
        admissionClass = Objects.requireNonNull(admissionClass, "admissionClass");
        allowedServers = Set.copyOf(Objects.requireNonNull(allowedServers, "allowedServers").stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet()));
        if (id.isEmpty()) {
            throw new IllegalArgumentException("profile id must not be empty");
        }
    }

    public boolean allowsServer(String serverName) {
        String normalized = Objects.requireNonNull(serverName, "serverName")
                .trim().toLowerCase(Locale.ROOT);
        return allowedServers.contains("*") || allowedServers.contains(normalized);
    }
}
