package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UuidV5Test {
    @Test
    void matchesKnownRfcCompatibleVector() {
        UUID dnsNamespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        assertEquals(
                UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d"),
                UuidV5.from(dnsNamespace, "python.org"));
    }
}
