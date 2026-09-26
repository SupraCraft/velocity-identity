package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.util.GameProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameProfileInvariantTest {
    @Test
    void detectsUuidNameAndPropertyMutation() {
        UUID id = UUID.fromString("12345678-1234-5678-9234-567812345678");
        GameProfile.Property texture =
                new GameProfile.Property("textures", "value-a", "signature-a");
        GameProfile expected = new GameProfile(id, "PlayerOne", List.of(texture));

        assertTrue(GameProfileInvariant.matchesExactly(
                expected,
                new GameProfile(id, "PlayerOne", List.of(texture))));

        assertFalse(GameProfileInvariant.matchesExactly(
                expected,
                new GameProfile(UUID.randomUUID(), "PlayerOne", List.of(texture))));

        assertFalse(GameProfileInvariant.matchesExactly(
                expected,
                new GameProfile(id, "OtherName", List.of(texture))));

        assertFalse(GameProfileInvariant.matchesExactly(
                expected,
                new GameProfile(
                        id,
                        "PlayerOne",
                        List.of(new GameProfile.Property(
                                "textures", "value-b", "signature-a")))));
    }
}
