package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.util.GameProfile;

import java.util.Objects;

public record ProviderResult(
        ProviderDisposition disposition,
        String providerId,
        CanonicalPrincipal principal,
        GameIdentity gameIdentity,
        GameProfile gameProfile,
        boolean externalTransferAllowed,
        String detail) {

    public ProviderResult {
        disposition = Objects.requireNonNull(disposition, "disposition");
        providerId = requireNonBlank(providerId, "providerId");
        detail = detail == null ? "" : detail;
        if (disposition == ProviderDisposition.AUTHENTICATED) {
            principal = Objects.requireNonNull(principal, "principal");
            gameIdentity = Objects.requireNonNull(gameIdentity, "gameIdentity");
            gameProfile = Objects.requireNonNull(gameProfile, "gameProfile");
            if (!gameIdentity.gameUuid().equals(gameProfile.getId())
                    || !gameIdentity.gameName().equals(gameProfile.getName())) {
                throw new IllegalArgumentException(
                        "authenticated GameIdentity must exactly match GameProfile UUID/name");
            }
        } else if (principal != null || gameIdentity != null || gameProfile != null) {
            throw new IllegalArgumentException("non-authenticated provider result cannot carry identity");
        }
    }

    public static ProviderResult authenticated(
            String providerId,
            CanonicalPrincipal principal,
            GameIdentity gameIdentity,
            GameProfile gameProfile,
            boolean externalTransferAllowed) {
        return new ProviderResult(
                ProviderDisposition.AUTHENTICATED,
                providerId,
                principal,
                gameIdentity,
                gameProfile,
                externalTransferAllowed,
                "");
    }

    public static ProviderResult denied(String providerId, String detail) {
        return new ProviderResult(
                ProviderDisposition.DENIED,
                providerId,
                null,
                null,
                null,
                false,
                detail);
    }

    public static ProviderResult error(String providerId, String detail) {
        return new ProviderResult(
                ProviderDisposition.ERROR,
                providerId,
                null,
                null,
                null,
                false,
                detail);
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
