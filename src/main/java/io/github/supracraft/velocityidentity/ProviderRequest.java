package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.util.GameProfile;

import java.util.Objects;

public record ProviderRequest(
        String mechanism,
        AdmissionProfile admissionProfile,
        GameProfile verifiedProfile,
        boolean onlineMode) {

    public ProviderRequest {
        mechanism = ProviderDescriptor.normalizeMechanism(mechanism);
        admissionProfile = Objects.requireNonNull(admissionProfile, "admissionProfile");
    }
}
