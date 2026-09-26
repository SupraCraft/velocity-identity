package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkloadTrustStoreTest {
    @TempDir
    Path temp;

    @Test
    void loadsExternallyProvisionedEd25519Binding() throws Exception {
        var pair = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
        Path file = temp.resolve("workloads.properties");
        Files.writeString(
                file,
                String.join(
                        System.lineSeparator(),
                        "issuer=urn:vip:test",
                        "workload.bot1.subject=gym/alpha/0",
                        "workload.bot1.public-key="
                                + Base64.getEncoder().encodeToString(
                                pair.getPublic().getEncoded()),
                        "workload.bot1.uuid=59d33a93-bbfe-4dcb-96fa-b2f6412163b9",
                        "workload.bot1.name=VipBot01"));

        WorkloadTrustStore store =
                WorkloadTrustStore.load(file);

        assertEquals("urn:vip:test", store.issuer());
        assertEquals(1, store.bindings().size());
        assertEquals(
                UUID.fromString(
                        "59d33a93-bbfe-4dcb-96fa-b2f6412163b9"),
                store.find("bot1")
                        .gameIdentity()
                        .gameUuid());
        assertEquals(
                "VipBot01",
                store.find("bot1")
                        .gameIdentity()
                        .gameName());
    }

    @Test
    void duplicateGameIdentityIsRejected() throws Exception {
        var first = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
        var second = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
        String uuid =
                "59d33a93-bbfe-4dcb-96fa-b2f6412163b9";
        Path file = temp.resolve("workloads.properties");
        Files.writeString(
                file,
                String.join(
                        System.lineSeparator(),
                        "issuer=urn:vip:test",
                        "workload.a.subject=a",
                        "workload.a.public-key="
                                + Base64.getEncoder().encodeToString(
                                first.getPublic().getEncoded()),
                        "workload.a.uuid=" + uuid,
                        "workload.a.name=VipBot01",
                        "workload.b.subject=b",
                        "workload.b.public-key="
                                + Base64.getEncoder().encodeToString(
                                second.getPublic().getEncoded()),
                        "workload.b.uuid=" + uuid,
                        "workload.b.name=VipBot02"));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkloadTrustStore.load(file));
    }
}
