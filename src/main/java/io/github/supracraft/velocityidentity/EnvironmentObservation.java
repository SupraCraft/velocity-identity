package io.github.supracraft.velocityidentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record EnvironmentObservation(
        String proxyName,
        String proxyVendor,
        String proxyVersion,
        boolean onlineMode,
        Map<String, String> configuredServers,
        Map<String, String> registeredServers,
        List<String> attemptConnectionOrder,
        Map<String, List<String>> forcedHosts,
        Map<String, String> loadedPlugins,
        Set<String> unknownFacts) {

    public EnvironmentObservation {
        proxyName = require(proxyName, "proxyName");
        proxyVendor = require(proxyVendor, "proxyVendor");
        proxyVersion = require(proxyVersion, "proxyVersion");
        configuredServers = sortedMap(configuredServers);
        registeredServers = sortedMap(registeredServers);
        attemptConnectionOrder = List.copyOf(Objects.requireNonNull(attemptConnectionOrder, "attemptConnectionOrder"));
        forcedHosts = sortedListMap(forcedHosts);
        loadedPlugins = sortedMap(loadedPlugins);
        unknownFacts = Collections.unmodifiableSet(new TreeSet<>(Objects.requireNonNull(unknownFacts, "unknownFacts")));
    }

    public String fingerprint() {
        StringBuilder out = new StringBuilder();
        out.append("proxy=").append(proxyName).append('|').append(proxyVendor).append('|').append(proxyVersion).append('\n');
        out.append("online=").append(onlineMode).append('\n');
        configuredServers.forEach((k, v) -> out.append("configured=").append(k).append('=').append(v).append('\n'));
        registeredServers.forEach((k, v) -> out.append("registered=").append(k).append('=').append(v).append('\n'));
        attemptConnectionOrder.forEach(v -> out.append("attempt=").append(v).append('\n'));
        forcedHosts.forEach((host, servers) -> {
            out.append("forced=").append(host).append('=');
            servers.forEach(server -> out.append(server).append(','));
            out.append('\n');
        });
        loadedPlugins.forEach((k, v) -> out.append("plugin=").append(k).append('=').append(v).append('\n'));
        unknownFacts.forEach(v -> out.append("unknown=").append(v).append('\n'));
        return Digests.sha256(out.toString());
    }

    public Set<String> knownServerNames() {
        TreeSet<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        result.addAll(configuredServers.keySet());
        result.addAll(registeredServers.keySet());
        return Collections.unmodifiableSet(result);
    }

    private static String require(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static Map<String, String> sortedMap(Map<String, String> input) {
        TreeMap<String, String> result = new TreeMap<>();
        result.putAll(Objects.requireNonNull(input, "input"));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<String>> sortedListMap(Map<String, List<String>> input) {
        TreeMap<String, List<String>> result = new TreeMap<>();
        Objects.requireNonNull(input, "input").forEach((key, value) -> {
            ArrayList<String> copy = new ArrayList<>(value);
            result.put(key, Collections.unmodifiableList(copy));
        });
        return Collections.unmodifiableMap(result);
    }
}
