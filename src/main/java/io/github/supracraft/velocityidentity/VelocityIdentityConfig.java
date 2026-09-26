package io.github.supracraft.velocityidentity;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public record VelocityIdentityConfig(AdmissionPolicy desiredPolicy) {
    public static VelocityIdentityConfig defaults() {
        return new VelocityIdentityConfig(AdmissionPolicy.onlineSessionOnly());
    }

    public static VelocityIdentityConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return defaults();
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }

        AdmissionClass defaultClass = parseClass(
                properties.getProperty("default.class", "ONLINE_SESSION"));
        Set<String> defaultServers = parseServers(
                properties.getProperty(
                        "default.servers",
                        defaultClass == AdmissionClass.ONLINE_SESSION ? "*" : ""));
        AdmissionProfile defaultProfile = new AdmissionProfile(
                "default",
                defaultClass,
                defaultServers);

        Set<String> hosts = new HashSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("host.") && key.endsWith(".class")) {
                hosts.add(key.substring(
                        "host.".length(),
                        key.length() - ".class".length()));
            }
        }

        Map<String, AdmissionProfile> profiles = new HashMap<>();
        for (String host : hosts) {
            AdmissionClass admissionClass = parseClass(
                    properties.getProperty("host." + host + ".class"));
            String fallback =
                    admissionClass == AdmissionClass.ONLINE_SESSION ? "*" : "";
            Set<String> servers = parseServers(
                    properties.getProperty(
                            "host." + host + ".servers",
                            fallback));
            profiles.put(
                    host,
                    new AdmissionProfile(host, admissionClass, servers));
        }

        return new VelocityIdentityConfig(
                new AdmissionPolicy(defaultProfile, profiles));
    }

    private static AdmissionClass parseClass(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "admission class must not be blank");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("MICROSOFT".equals(normalized) || "NATIVE".equals(normalized)) {
            return AdmissionClass.ONLINE_SESSION;
        }
        return AdmissionClass.valueOf(normalized);
    }

    private static Set<String> parseServers(String value) {
        Set<String> result = new HashSet<>();
        for (String item : value.split(",")) {
            String normalized = item.trim();
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }
}
