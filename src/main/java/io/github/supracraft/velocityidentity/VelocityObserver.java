package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.ProxyVersion;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class VelocityObserver {
    private VelocityObserver() {
    }

    public static EnvironmentObservation observe(ProxyServer server) {
        ProxyVersion version = server.getVersion();
        ProxyConfig config = server.getConfiguration();

        Map<String, String> registered = new TreeMap<>();
        for (RegisteredServer backend : server.getAllServers()) {
            registered.put(
                    backend.getServerInfo().getName(),
                    backend.getServerInfo().getAddress().toString());
        }

        Map<String, String> plugins = new TreeMap<>();
        for (PluginContainer plugin : server.getPluginManager().getPlugins()) {
            String pluginVersion = plugin.getDescription().getVersion().orElse("unknown");
            plugins.put(plugin.getDescription().getId(), pluginVersion);
        }

        return new EnvironmentObservation(
                version.getName(),
                version.getVendor(),
                version.getVersion(),
                config.isOnlineMode(),
                config.getServers(),
                registered,
                config.getAttemptConnectionOrder(),
                config.getForcedHosts(),
                plugins,
                Set.of("player-info-forwarding-mode"));
    }
}
