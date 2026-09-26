package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PendingLoginKeyTest {
    @Test
    void sameGameIdentityOnDifferentConnectionsDoesNotAlias() {
        UUID id = UUID.fromString("12345678-1234-5678-9234-567812345678");
        PendingLoginKey first =
                new PendingLoginKey(id, new InetSocketAddress("127.0.0.1", 40001));
        PendingLoginKey second =
                new PendingLoginKey(id, new InetSocketAddress("127.0.0.1", 40002));

        assertNotEquals(first, second);
        assertEquals(id, first.gameUuid());
        assertEquals(id, second.gameUuid());
    }
}
