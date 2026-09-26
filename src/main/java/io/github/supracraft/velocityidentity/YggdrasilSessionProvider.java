package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.util.GameProfile;

import java.util.Set;

public final class YggdrasilSessionProvider implements IdentityProvider {
    public static final String MECHANISM = "velocity-online-session";

    private final SessionAuthority authority;
    private final ProviderDescriptor descriptor;

    public YggdrasilSessionProvider(SessionAuthority authority) {
        this.authority = authority;
        this.descriptor = new ProviderDescriptor(
                "native-yggdrasil-session",
                authority.issuer(),
                100,
                Set.of(AdmissionClass.NATIVE),
                Set.of(MECHANISM),
                Set.of(
                        ProviderCapability.SESSION_VERIFICATION,
                        ProviderCapability.GAME_IDENTITY,
                        ProviderCapability.PROFILE_PROPERTIES));
    }

    @Override
    public ProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ProviderClaim claim(ProviderRequest request) {
        return descriptor.supports(
                request.admissionProfile().admissionClass(),
                request.mechanism())
                ? ProviderClaim.CLAIM
                : ProviderClaim.ABSTAIN;
    }

    @Override
    public ProviderResult authenticate(ProviderRequest request) {
        if (!request.onlineMode()) {
            return ProviderResult.denied(
                    descriptor.id(),
                    "native session provider requires a Velocity-verified online session");
        }

        GameProfile profile = request.verifiedProfile();
        if (profile == null) {
            return ProviderResult.error(
                    descriptor.id(),
                    "Velocity did not supply a verified GameProfile");
        }

        CanonicalPrincipal principal = new CanonicalPrincipal(
                authority.issuer(),
                profile.getId().toString(),
                PrincipalKind.HUMAN);
        GameIdentity gameIdentity = new GameIdentity(
                profile.getId(),
                profile.getName());

        return ProviderResult.authenticated(
                descriptor.id(),
                principal,
                gameIdentity,
                profile,
                authority.kind() == SessionAuthority.Kind.MOJANG);
    }
}
