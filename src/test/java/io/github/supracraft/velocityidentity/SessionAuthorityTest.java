package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionAuthorityTest {
    @Test
    void absentOverrideUsesMojangAuthority() {
        SessionAuthority authority =
                SessionAuthority.fromConfiguredValue(null);

        assertEquals(SessionAuthority.Kind.MOJANG, authority.kind());
        assertEquals(
                "https://sessionserver.mojang.com",
                authority.issuer());
        assertTrue(authority.secureTransport());
    }

    @Test
    void derivesAsterYggdrasilIssuerFromStandardEndpoint() {
        SessionAuthority authority =
                SessionAuthority.fromConfiguredValue(
                        "https://identity.example/api/yggdrasil/sessionserver/session/minecraft/hasJoined");

        assertEquals(
                SessionAuthority.Kind.CUSTOM_YGGDRASIL,
                authority.kind());
        assertEquals(
                "https://identity.example/api/yggdrasil",
                authority.issuer());
        assertTrue(authority.secureTransport());
    }

    @Test
    void derivesDraslIssuerFromSessionEndpoint() {
        SessionAuthority authority =
                SessionAuthority.fromConfiguredValue(
                        "http://127.0.0.1:18080/session/minecraft/hasJoined");

        assertEquals(
                "http://127.0.0.1:18080",
                authority.issuer());
        assertFalse(authority.secureTransport());
    }

    @Test
    void rejectsEndpointWithQuery() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionAuthority.fromConfiguredValue(
                        "https://identity.example/session/minecraft/hasJoined?x=1"));
    }
}
