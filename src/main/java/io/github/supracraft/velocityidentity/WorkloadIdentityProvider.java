package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.util.GameProfile;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Set;

public final class WorkloadIdentityProvider implements IdentityProvider {
    public static final String PROVIDER_ID =
            "vip-workload-ed25519";
    public static final String MECHANISM =
            "vip-login-plugin-ed25519-v1";

    private final WorkloadTrustRuntime trustRuntime;

    public WorkloadIdentityProvider(
            WorkloadTrustRuntime trustRuntime) {
        this.trustRuntime = java.util.Objects.requireNonNull(
                trustRuntime,
                "trustRuntime");
    }

    @Override
    public ProviderDescriptor descriptor() {
        return new ProviderDescriptor(
                PROVIDER_ID,
                trustRuntime.current().issuer(),
                200,
                Set.of(AdmissionClass.WORKLOAD),
                Set.of(MECHANISM),
                Set.of(
                        ProviderCapability.PRINCIPAL_AUTHENTICATION,
                        ProviderCapability.GAME_IDENTITY));
    }

    @Override
    public ProviderClaim claim(ProviderRequest request) {
        return ProviderClaim.CLAIM;
    }

    @Override
    public ProviderResult authenticate(
            ProviderRequest request) {
        if (request.onlineMode()) {
            return ProviderResult.denied(
                    PROVIDER_ID,
                    "workload mechanism requires offline proxy login");
        }
        WorkloadPresentation presentation =
                request.workloadPresentation();
        if (presentation == null) {
            return ProviderResult.denied(
                    PROVIDER_ID,
                    "workload presentation missing");
        }

        WorkloadChallengeProtocol.WorkloadResponse response;
        try {
            response = WorkloadChallengeProtocol.parseResponse(
                    presentation.response());
        } catch (IllegalArgumentException invalid) {
            return ProviderResult.denied(
                    PROVIDER_ID,
                    "malformed workload response");
        }

        WorkloadTrustStore store = trustRuntime.current();
        WorkloadIdentityBinding binding =
                store.find(response.keyId());
        if (binding == null) {
            return ProviderResult.denied(
                    PROVIDER_ID,
                    "unknown workload key");
        }

        try {
            if (!WorkloadChallengeProtocol.verify(
                    binding.publicKey(),
                    presentation.challenge(),
                    response.keyId(),
                    response.signature())) {
                return ProviderResult.denied(
                        PROVIDER_ID,
                        "invalid workload signature");
            }
        } catch (GeneralSecurityException error) {
            return ProviderResult.error(
                    PROVIDER_ID,
                    "workload signature verification failed");
        }

        GameIdentity identity = binding.gameIdentity();
        return ProviderResult.authenticated(
                PROVIDER_ID,
                new CanonicalPrincipal(
                        store.issuer(),
                        binding.subject(),
                        PrincipalKind.WORKLOAD),
                identity,
                new GameProfile(
                        identity.gameUuid(),
                        identity.gameName(),
                        List.of()),
                false);
    }
}
