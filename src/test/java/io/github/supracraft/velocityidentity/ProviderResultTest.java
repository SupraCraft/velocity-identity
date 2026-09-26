package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.util.GameProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderResultTest {
    @Test
    void authenticatedResultRejectsProfileIdentityMismatch() {
        UUID canonical = UUID.fromString(
                "11111111-2222-4333-8444-555555555555");
        UUID substituted = UUID.fromString(
                "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

        assertThrows(
                IllegalArgumentException.class,
                () -> ProviderResult.authenticated(
                        "provider",
                        new CanonicalPrincipal(
                                "https://issuer.example",
                                "subject",
                                PrincipalKind.HUMAN),
                        new GameIdentity(canonical, "CanonicalUser"),
                        new GameProfile(
                                substituted,
                                "SubstitutedUser",
                                List.of()),
                        false));
    }
}
