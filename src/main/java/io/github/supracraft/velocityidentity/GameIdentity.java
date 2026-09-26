package io.github.supracraft.velocityidentity;

import java.util.Objects;
import java.util.UUID;

public record GameIdentity(UUID gameUuid, String gameName) {
    public GameIdentity {
        gameUuid = Objects.requireNonNull(gameUuid, "gameUuid");
        gameName = GameNames.requireValid(gameName);
    }
}
