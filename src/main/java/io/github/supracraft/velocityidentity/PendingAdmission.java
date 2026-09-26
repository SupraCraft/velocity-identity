package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.util.GameProfile;

import java.util.Objects;

public record PendingAdmission(
        AdmissionProfile profile,
        CanonicalPrincipal principal,
        String providerId,
        GameIdentity gameIdentity,
        GameProfile expectedProfile,
        boolean externalTransferAllowed,
        long generation,
        long createdNanos) {

    public PendingAdmission {
        profile = Objects.requireNonNull(profile, "profile");
        if (providerId != null && providerId.isBlank()) {
            throw new IllegalArgumentException(
                    "providerId must not be blank when present");
        }
    }

    public PendingAdmission withProviderResult(
            ProviderResult result) {
        Objects.requireNonNull(result, "result");
        if (result.disposition()
                != ProviderDisposition.AUTHENTICATED) {
            throw new IllegalArgumentException(
                    "provider result must be authenticated");
        }
        return new PendingAdmission(
                profile,
                result.principal(),
                result.providerId(),
                result.gameIdentity(),
                result.gameProfile(),
                result.externalTransferAllowed(),
                generation,
                createdNanos);
    }
}
