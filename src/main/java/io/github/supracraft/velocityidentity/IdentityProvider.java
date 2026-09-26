package io.github.supracraft.velocityidentity;

public interface IdentityProvider {
    ProviderDescriptor descriptor();

    ProviderClaim claim(ProviderRequest request);

    ProviderResult authenticate(ProviderRequest request);
}
