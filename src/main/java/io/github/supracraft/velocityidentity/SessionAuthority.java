package io.github.supracraft.velocityidentity;

import java.net.URI;
import java.util.List;
import java.util.Objects;

public record SessionAuthority(
        Kind kind,
        String issuer,
        URI hasJoinedEndpoint) {

    public static final String SESSION_SERVER_PROPERTY = "mojang.sessionserver";
    public static final URI MOJANG_HAS_JOINED = URI.create(
            "https://sessionserver.mojang.com/session/minecraft/hasJoined");

    private static final List<String> KNOWN_SUFFIXES = List.of(
            "/sessionserver/session/minecraft/hasJoined",
            "/session/minecraft/hasJoined");

    public SessionAuthority {
        kind = Objects.requireNonNull(kind, "kind");
        issuer = requireNonBlank(issuer, "issuer");
        hasJoinedEndpoint = validateEndpoint(hasJoinedEndpoint);
    }

    public static SessionAuthority observeConfigured() {
        return fromConfiguredValue(System.getProperty(SESSION_SERVER_PROPERTY));
    }

    public static SessionAuthority fromConfiguredValue(String configured) {
        if (configured == null || configured.isBlank()) {
            return mojang();
        }

        URI endpoint = validateEndpoint(URI.create(configured.trim()));
        if (sameEndpoint(endpoint, MOJANG_HAS_JOINED)) {
            return mojang();
        }
        return new SessionAuthority(
                Kind.CUSTOM_YGGDRASIL,
                deriveIssuer(endpoint),
                endpoint);
    }

    public static SessionAuthority mojang() {
        return new SessionAuthority(
                Kind.MOJANG,
                "https://sessionserver.mojang.com",
                MOJANG_HAS_JOINED);
    }

    public boolean secureTransport() {
        return "https".equalsIgnoreCase(hasJoinedEndpoint.getScheme());
    }

    private static URI validateEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String scheme = endpoint.getScheme();
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(
                    "session server must be an absolute http(s) URI");
        }
        if (endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException(
                    "session server endpoint must not contain query or fragment");
        }
        return endpoint.normalize();
    }

    private static boolean sameEndpoint(URI left, URI right) {
        return left.normalize().toString().equalsIgnoreCase(right.normalize().toString());
    }

    private static String deriveIssuer(URI endpoint) {
        String path = endpoint.getPath() == null ? "" : endpoint.getPath();
        String root = path;
        for (String suffix : KNOWN_SUFFIXES) {
            if (path.endsWith(suffix)) {
                root = path.substring(0, path.length() - suffix.length());
                break;
            }
        }
        while (root.endsWith("/") && !root.isEmpty()) {
            root = root.substring(0, root.length() - 1);
        }

        StringBuilder issuer = new StringBuilder()
                .append(endpoint.getScheme().toLowerCase())
                .append("://")
                .append(endpoint.getAuthority());
        if (!root.isEmpty()) {
            issuer.append(root);
        }
        return issuer.toString();
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    public enum Kind {
        MOJANG,
        CUSTOM_YGGDRASIL
    }
}
