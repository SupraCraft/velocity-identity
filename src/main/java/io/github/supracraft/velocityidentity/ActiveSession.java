package io.github.supracraft.velocityidentity;

import java.util.Objects;
import java.util.UUID;

public record ActiveSession(
        UUID playerUuid,
        AdmissionProfile profile,
        GameIdentity gameIdentity) {

    public ActiveSession {
        playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        profile = Objects.requireNonNull(profile, "profile");
    }
}
