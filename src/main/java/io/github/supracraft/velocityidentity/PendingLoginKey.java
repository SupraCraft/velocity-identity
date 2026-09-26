package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.proxy.InboundConnection;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.UUID;

public record PendingLoginKey(UUID gameUuid, InetSocketAddress remoteAddress) {
    public PendingLoginKey {
        gameUuid = Objects.requireNonNull(gameUuid, "gameUuid");
        remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
    }

    public static PendingLoginKey of(UUID gameUuid, InboundConnection connection) {
        return new PendingLoginKey(gameUuid, connection.getRemoteAddress());
    }
}
