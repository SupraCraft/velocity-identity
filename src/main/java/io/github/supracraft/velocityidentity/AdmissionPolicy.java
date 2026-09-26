package io.github.supracraft.velocityidentity;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record AdmissionPolicy(
        AdmissionProfile defaultProfile,
        Map<String, AdmissionProfile> profilesByHost) {

    public AdmissionPolicy {
        defaultProfile = Objects.requireNonNull(defaultProfile, "defaultProfile");
        TreeMap<String, AdmissionProfile> normalized = new TreeMap<>();
        Objects.requireNonNull(profilesByHost, "profilesByHost").forEach(
                (host, profile) -> normalized.put(normalizeHost(host), Objects.requireNonNull(profile, "profile")));
        profilesByHost = Collections.unmodifiableMap(normalized);
    }

    public static AdmissionPolicy microsoftOnly() {
        return new AdmissionPolicy(
                new AdmissionProfile("default", AdmissionClass.MICROSOFT, java.util.Set.of("*")),
                Map.of());
    }

    public AdmissionProfile select(String host) {
        if (host == null || host.isBlank()) {
            return defaultProfile;
        }
        return profilesByHost.getOrDefault(normalizeHost(host), defaultProfile);
    }

    public String fingerprint() {
        StringBuilder canonical = new StringBuilder();
        appendProfile(canonical, "default", defaultProfile);
        profilesByHost.forEach((host, profile) -> appendProfile(canonical, host, profile));
        return Digests.sha256(canonical.toString());
    }

    private static void appendProfile(StringBuilder out, String host, AdmissionProfile profile) {
        out.append(host).append('|')
                .append(profile.id()).append('|')
                .append(profile.admissionClass()).append('|');
        profile.allowedServers().stream().sorted().forEach(server -> out.append(server).append(','));
        out.append('\n');
    }

    public static String normalizeHost(String host) {
        String normalized = Objects.requireNonNull(host, "host").trim().toLowerCase(Locale.ROOT);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
