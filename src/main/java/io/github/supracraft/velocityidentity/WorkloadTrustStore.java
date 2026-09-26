package io.github.supracraft.velocityidentity;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

public final class WorkloadTrustStore {
    public static final String DEFAULT_ISSUER =
            "urn:supracraft:vip:workload";

    private final String issuer;
    private final Map<String, WorkloadIdentityBinding> bindings;
    private final String fingerprint;

    public WorkloadTrustStore(
            String issuer,
            Map<String, WorkloadIdentityBinding> bindings) {
        this.issuer = requireNonBlank(issuer, "issuer");
        this.bindings = Collections.unmodifiableMap(
                new HashMap<>(Objects.requireNonNull(bindings, "bindings")));
        validateUniqueGameIdentities(this.bindings.values());
        this.fingerprint = calculateFingerprint(this.issuer, this.bindings);
    }

    public static WorkloadTrustStore empty() {
        return new WorkloadTrustStore(DEFAULT_ISSUER, Map.of());
    }

    public static WorkloadTrustStore load(Path path)
            throws IOException, GeneralSecurityException {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            return empty();
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }

        String issuer = properties.getProperty(
                "issuer",
                DEFAULT_ISSUER).trim();
        Set<String> keyIds = new HashSet<>();
        Set<String> allowedFields = Set.of(
                "subject",
                "public-key",
                "uuid",
                "name");
        for (String property : properties.stringPropertyNames()) {
            if ("issuer".equals(property)) {
                continue;
            }
            if (!property.startsWith("workload.")) {
                throw new IllegalArgumentException(
                        "unknown workload trust property: " + property);
            }
            String remainder =
                    property.substring("workload.".length());
            int separator = remainder.lastIndexOf('.');
            if (separator <= 0
                    || separator == remainder.length() - 1) {
                throw new IllegalArgumentException(
                        "malformed workload trust property: " + property);
            }
            String keyId = remainder.substring(0, separator);
            String field = remainder.substring(separator + 1);
            if (!allowedFields.contains(field)) {
                throw new IllegalArgumentException(
                        "unknown workload field: " + property);
            }
            keyIds.add(keyId);
        }

        Map<String, WorkloadIdentityBinding> bindings = new HashMap<>();
        KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
        for (String keyId : keyIds) {
            String prefix = "workload." + keyId + ".";
            String subject = required(properties, prefix + "subject");
            String encodedKey = required(properties, prefix + "public-key");
            String uuidText = required(properties, prefix + "uuid");
            String name = required(properties, prefix + "name");

            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(encodedKey);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "invalid base64 public key for workload " + keyId,
                        invalid);
            }
            PublicKey publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(keyBytes));

            WorkloadIdentityBinding binding =
                    new WorkloadIdentityBinding(
                            keyId,
                            subject,
                            publicKey,
                            new GameIdentity(
                                    UUID.fromString(uuidText),
                                    name));
            if (bindings.putIfAbsent(keyId, binding) != null) {
                throw new IllegalArgumentException(
                        "duplicate workload keyId: " + keyId);
            }
        }

        return new WorkloadTrustStore(issuer, bindings);
    }

    public String issuer() {
        return issuer;
    }

    public Map<String, WorkloadIdentityBinding> bindings() {
        return bindings;
    }

    public WorkloadIdentityBinding find(String keyId) {
        return bindings.get(keyId);
    }

    public boolean isEmpty() {
        return bindings.isEmpty();
    }

    public String fingerprint() {
        return fingerprint;
    }

    public WorkloadTrustSummary summary() {
        return new WorkloadTrustSummary(
                issuer,
                bindings.size(),
                bindings.keySet().stream().sorted().toList(),
                fingerprint);
    }

    private static void validateUniqueGameIdentities(
            Iterable<WorkloadIdentityBinding> values) {
        Map<UUID, WorkloadIdentityBinding> byUuid =
                new HashMap<>();
        Map<String, WorkloadIdentityBinding> byName =
                new HashMap<>();
        Set<String> publicKeys = new HashSet<>();

        for (WorkloadIdentityBinding binding : values) {
            String publicKey = Base64.getEncoder()
                    .encodeToString(
                            binding.publicKey().getEncoded());
            if (!publicKeys.add(publicKey)) {
                throw new IllegalArgumentException(
                        "the same workload public key cannot identify multiple key IDs");
            }

            WorkloadIdentityBinding uuidOwner =
                    byUuid.putIfAbsent(
                            binding.gameIdentity().gameUuid(),
                            binding);
            if (uuidOwner != null
                    && !sameCanonicalIdentity(
                    uuidOwner,
                    binding)) {
                throw new IllegalArgumentException(
                        "workload game UUID is assigned to multiple identities: "
                                + binding.gameIdentity().gameUuid());
            }

            String lower = binding.gameIdentity()
                    .gameName()
                    .toLowerCase(java.util.Locale.ROOT);
            WorkloadIdentityBinding nameOwner =
                    byName.putIfAbsent(lower, binding);
            if (nameOwner != null
                    && !sameCanonicalIdentity(
                    nameOwner,
                    binding)) {
                throw new IllegalArgumentException(
                        "workload game name is assigned to multiple identities: "
                                + binding.gameIdentity().gameName());
            }
        }
    }

    private static boolean sameCanonicalIdentity(
            WorkloadIdentityBinding first,
            WorkloadIdentityBinding second) {
        return first.subject().equals(second.subject())
                && first.gameIdentity().equals(
                second.gameIdentity());
    }

    private static String calculateFingerprint(
            String issuer,
            Map<String, WorkloadIdentityBinding> bindings) {
        List<String> keys = new ArrayList<>(bindings.keySet());
        Collections.sort(keys);
        StringBuilder canonical = new StringBuilder()
                .append(issuer).append('\n');
        for (String key : keys) {
            WorkloadIdentityBinding binding = bindings.get(key);
            canonical.append(key).append('|')
                    .append(binding.subject()).append('|')
                    .append(binding.gameIdentity().gameUuid()).append('|')
                    .append(binding.gameIdentity().gameName()).append('|')
                    .append(Base64.getEncoder().encodeToString(
                            binding.publicKey().getEncoded()))
                    .append('\n');
        }
        return Digests.sha256(canonical.toString());
    }

    private static String required(
            Properties properties,
            String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "missing required workload property: " + key);
        }
        return value.trim();
    }

    private static String requireNonBlank(
            String value,
            String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }
}
