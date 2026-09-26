package io.github.supracraft.velocityidentity;

import java.util.Objects;

public record ActiveSession(
        AdmissionProfile profile,
        CanonicalPrincipal principal,
        String providerId,
        GameIdentity gameIdentity,
        boolean externalTransferAllowed,
        long generation) {

    public ActiveSession {
        profile = Objects.requireNonNull(profile, "profile");
        providerId = Objects.requireNonNull(providerId, "providerId").trim();
        gameIdentity = Objects.requireNonNull(gameIdentity, "gameIdentity");
        if (providerId.isEmpty()) {
            throw new IllegalArgumentException(
                    "providerId must not be blank");
        }
    }
}
