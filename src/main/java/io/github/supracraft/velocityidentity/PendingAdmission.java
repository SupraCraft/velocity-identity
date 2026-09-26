package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.util.GameProfile;

import java.util.Objects;

public record PendingAdmission(
        AdmissionProfile profile,
        GameIdentity gameIdentity,
        GameProfile expectedProfile,
        long generation,
        long createdNanos) {

    public PendingAdmission {
        profile = Objects.requireNonNull(profile, "profile");
    }

    public PendingAdmission withExpectedProfile(GameProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return new PendingAdmission(
                this.profile,
                new GameIdentity(profile.getId(), profile.getName()),
                profile,
                this.generation,
                this.createdNanos);
    }
}
