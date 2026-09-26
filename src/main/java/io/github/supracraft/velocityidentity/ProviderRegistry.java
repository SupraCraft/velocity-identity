package io.github.supracraft.velocityidentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ProviderRegistry {
    private final List<IdentityProvider> providers;

    public ProviderRegistry(List<? extends IdentityProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        ArrayList<IdentityProvider> ordered = new ArrayList<>(providers);
        ordered.sort(Comparator
                .comparingInt((IdentityProvider provider) -> provider.descriptor().priority())
                .reversed()
                .thenComparing(provider -> provider.descriptor().id()));
        validate(ordered);
        this.providers = List.copyOf(ordered);
    }

    public List<ProviderDescriptor> descriptors() {
        return providers.stream().map(IdentityProvider::descriptor).toList();
    }

    public ProviderResult authenticate(ProviderRequest request) {
        Objects.requireNonNull(request, "request");

        for (IdentityProvider provider : providers) {
            ProviderDescriptor descriptor = provider.descriptor();
            if (!descriptor.supports(
                    request.admissionProfile().admissionClass(),
                    request.mechanism())) {
                continue;
            }

            final ProviderClaim claim;
            try {
                claim = Objects.requireNonNull(provider.claim(request), "provider claim");
            } catch (RuntimeException error) {
                return ProviderResult.error(
                        descriptor.id(),
                        "provider claim failed");
            }

            if (claim == ProviderClaim.ABSTAIN) {
                continue;
            }

            try {
                ProviderResult result = Objects.requireNonNull(
                        provider.authenticate(request),
                        "provider result");
                if (!descriptor.id().equals(result.providerId())) {
                    return ProviderResult.error(
                            descriptor.id(),
                            "provider returned a mismatched provider id");
                }
                return result;
            } catch (RuntimeException error) {
                return ProviderResult.error(
                        descriptor.id(),
                        "provider authentication failed");
            }
        }

        return ProviderResult.denied(
                "provider-registry",
                "no provider claimed the presented admission mechanism");
    }

    private static void validate(List<IdentityProvider> providers) {
        Set<String> ids = new HashSet<>();
        for (IdentityProvider provider : providers) {
            ProviderDescriptor descriptor =
                    Objects.requireNonNull(provider, "provider").descriptor();
            if (!ids.add(descriptor.id())) {
                throw new IllegalArgumentException(
                        "duplicate provider id: " + descriptor.id());
            }
        }

        for (int left = 0; left < providers.size(); left++) {
            ProviderDescriptor a = providers.get(left).descriptor();
            for (int right = left + 1; right < providers.size(); right++) {
                ProviderDescriptor b = providers.get(right).descriptor();
                if (a.priority() != b.priority()) {
                    continue;
                }
                for (AdmissionClass admissionClass : a.admissionClasses()) {
                    if (!b.admissionClasses().contains(admissionClass)) {
                        continue;
                    }
                    for (String mechanism : a.mechanisms()) {
                        if (b.mechanisms().contains(mechanism)) {
                            throw new IllegalArgumentException(
                                    "equal-priority providers overlap: "
                                            + a.id() + " and " + b.id()
                                            + " for " + admissionClass + "/" + mechanism);
                        }
                    }
                }
            }
        }
    }
}
