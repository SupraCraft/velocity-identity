package io.github.supracraft.velocityidentity;

import java.util.Objects;

public record ActiveSession(
        AdmissionProfile profile,
        GameIdentity gameIdentity,
        long generation) {

    public ActiveSession {
        profile = Objects.requireNonNull(profile, "profile");
        gameIdentity = Objects.requireNonNull(gameIdentity, "gameIdentity");
    }
}
