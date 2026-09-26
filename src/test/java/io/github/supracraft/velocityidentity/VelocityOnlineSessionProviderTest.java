package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.util.GameProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityOnlineSessionProviderTest {
    private static final AdmissionProfile NATIVE =
            new AdmissionProfile(
                    "native",
                    AdmissionClass.ONLINE_SESSION,
                    Set.of("*"));

    @Test
    void customYggdrasilNormalizesVerifiedProfile() {
        SessionAuthority authority =
                SessionAuthority.fromConfiguredValue(
                        "https://identity.example/api/yggdrasil/sessionserver/session/minecraft/hasJoined");
        VelocityOnlineSessionProvider provider =
                new VelocityOnlineSessionProvider(authority);
        GameProfile profile = profile();

        ProviderResult result = provider.authenticate(
                new ProviderRequest(
                        VelocityOnlineSessionProvider.MECHANISM,
                        NATIVE,
                        profile,
                        true));

        assertEquals(
                ProviderDisposition.AUTHENTICATED,
                result.disposition());
        assertEquals(
                "https://identity.example/api/yggdrasil",
                result.principal().issuer());
        assertEquals(
                profile.getId().toString(),
                result.principal().subject());
        assertEquals(profile.getId(), result.gameIdentity().gameUuid());
        assertEquals(profile.getName(), result.gameIdentity().gameName());
        assertSame(profile, result.gameProfile());
        assertFalse(result.externalTransferAllowed());
    }

    @Test
    void mojangNativeSessionMayTransferExternally() {
        VelocityOnlineSessionProvider provider =
                new VelocityOnlineSessionProvider(
                        SessionAuthority.mojang());

        ProviderResult result = provider.authenticate(
                new ProviderRequest(
                        VelocityOnlineSessionProvider.MECHANISM,
                        NATIVE,
                        profile(),
                        true));

        assertTrue(result.externalTransferAllowed());
    }

    @Test
    void offlineProfileIsRejectedByNativeSessionProvider() {
        VelocityOnlineSessionProvider provider =
                new VelocityOnlineSessionProvider(
                        SessionAuthority.mojang());

        ProviderResult result = provider.authenticate(
                new ProviderRequest(
                        VelocityOnlineSessionProvider.MECHANISM,
                        NATIVE,
                        profile(),
                        false));

        assertEquals(
                ProviderDisposition.DENIED,
                result.disposition());
    }

    private static GameProfile profile() {
        return new GameProfile(
                UUID.fromString(
                        "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
                "YggUser",
                List.of());
    }
}
