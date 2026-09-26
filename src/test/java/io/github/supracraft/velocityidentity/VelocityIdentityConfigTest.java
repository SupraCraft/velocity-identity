package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VelocityIdentityConfigTest {
    @TempDir
    Path temp;

    @Test
    void absentConfigIsOnlineSessionOnly() throws Exception {
        VelocityIdentityConfig config =
                VelocityIdentityConfig.load(
                        temp.resolve("missing.properties"));
        assertEquals(
                AdmissionClass.ONLINE_SESSION,
                config.desiredPolicy()
                        .defaultProfile()
                        .admissionClass());
    }

    @Test
    void parsesExplicitGuestHost() throws Exception {
        Path configFile =
                temp.resolve("velocity-identity.properties");
        Files.writeString(
                configFile,
                String.join(
                        System.lineSeparator(),
                        "default.class=NATIVE",
                        "default.servers=*",
                        "host.guest.example.net.class=GUEST",
                        "host.guest.example.net.servers=guest-gym"));

        VelocityIdentityConfig config =
                VelocityIdentityConfig.load(configFile);

        assertEquals(
                AdmissionClass.GUEST,
                config.desiredPolicy()
                        .select("guest.example.net")
                        .admissionClass());
    }

    @Test
    void acceptsLegacyMicrosoftAliasAsOnlineSession() throws Exception {
        Path configFile =
                temp.resolve("velocity-identity.properties");
        Files.writeString(
                configFile,
                String.join(
                        System.lineSeparator(),
                        "default.class=MICROSOFT",
                        "default.servers=*"));

        VelocityIdentityConfig config =
                VelocityIdentityConfig.load(configFile);

        assertEquals(
                AdmissionClass.ONLINE_SESSION,
                config.desiredPolicy()
                        .defaultProfile()
                        .admissionClass());
    }
}
