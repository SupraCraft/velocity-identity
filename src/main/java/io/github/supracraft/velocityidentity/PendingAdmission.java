package io.github.supracraft.velocityidentity;

import java.util.Objects;

public record PendingAdmission(
        AdmissionProfile profile,
        GameIdentity gameIdentity,
        long createdNanos) {

    public PendingAdmission {
        profile = Objects.requireNonNull(profile, "profile");
    }
}
