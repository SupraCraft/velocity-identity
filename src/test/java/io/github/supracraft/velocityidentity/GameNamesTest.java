package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameNamesTest {
    @Test
    void acceptsVanillaCompatibleNamesAndRejectsConfusables() {
        assertEquals("GymBot_17", GameNames.requireValid("GymBot_17"));
        assertThrows(IllegalArgumentException.class, () -> GameNames.requireValid("bad name"));
        assertThrows(IllegalArgumentException.class, () -> GameNames.requireValid("böt"));
        assertThrows(IllegalArgumentException.class, () -> GameNames.requireValid("12345678901234567"));
    }

    @Test
    void guestNameIsDerivedFromProxyOwnedUuid() {
        UUID uuid = UUID.fromString("12345678-1234-5678-9234-567812345678");
        assertEquals("Guest_12345678", GameNames.guest(uuid));
    }
}
