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
    void overlappingDifferentKeysMayRotateExactIdentity() throws Exception {
        var first = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
        var second = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
        Path file = temp.resolve("workloads.properties");
        Files.writeString(
                file,
                String.join(
                        System.lineSeparator(),
                        "issuer=urn:vip:test",
                        "workload.old.subject=gym/alpha/0",
                        "workload.old.public-key="
                                + Base64.getEncoder().encodeToString(
                                first.getPublic().getEncoded()),
                        "workload.old.uuid=59d33a93-bbfe-4dcb-96fa-b2f6412163b9",
                        "workload.old.name=VipBot01",
                        "workload.new.subject=gym/alpha/0",
                        "workload.new.public-key="
                                + Base64.getEncoder().encodeToString(
                                second.getPublic().getEncoded()),
                        "workload.new.uuid=59d33a93-bbfe-4dcb-96fa-b2f6412163b9",
                        "workload.new.name=VipBot01"));

        WorkloadTrustStore store =
                WorkloadTrustStore.load(file);

        assertEquals(2, store.bindings().size());
    }

    @Test
    void samePublicKeyCannotRepresentMultipleKeyIds() throws Exception {
        var pair = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
        String encoded = Base64.getEncoder()
                .encodeToString(
                        pair.getPublic().getEncoded());
        Path file = temp.resolve("workloads.properties");
        Files.writeString(
                file,
                String.join(
                        System.lineSeparator(),
                        "issuer=urn:vip:test",
                        "workload.a.subject=gym/alpha/0",
                        "workload.a.public-key=" + encoded,
                        "workload.a.uuid=59d33a93-bbfe-4dcb-96fa-b2f6412163b9",
                        "workload.a.name=VipBot01",
                        "workload.b.subject=gym/bravo/0",
                        "workload.b.public-key=" + encoded,
                        "workload.b.uuid=6f3a7910-78c7-4bd5-8729-729082a7ce40",
                        "workload.b.name=VipBot02"));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkloadTrustStore.load(file));
    }

    @Test
    void partialWorkloadRecordIsRejected() throws Exception {
        var pair = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
        Path file = temp.resolve("workloads.properties");
        Files.writeString(
                file,
                String.join(
                        System.lineSeparator(),
                        "issuer=urn:vip:test",
                        "workload.bot1.public-key="
                                + Base64.getEncoder().encodeToString(
                                pair.getPublic().getEncoded()),
                        "workload.bot1.uuid=59d33a93-bbfe-4dcb-96fa-b2f6412163b9",
                        "workload.bot1.name=VipBot01"));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkloadTrustStore.load(file));
    }

    @Test
    void unknownWorkloadFieldIsRejected() throws Exception {
        Path file = temp.resolve("workloads.properties");
        Files.writeString(
                file,
                String.join(
                        System.lineSeparator(),
                        "issuer=urn:vip:test",
                        "workload.bot1.subject=gym/alpha/0",
                        "workload.bot1.unexpected=value"));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkloadTrustStore.load(file));
    }

    @Test
    void duplicateGameNameIsRejectedCaseInsensitively() throws Exception {
        var first = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
        var second = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
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
                        "workload.a.uuid=59d33a93-bbfe-4dcb-96fa-b2f6412163b9",
                        "workload.a.name=VipBot01",
                        "workload.b.subject=b",
                        "workload.b.public-key="
                                + Base64.getEncoder().encodeToString(
                                second.getPublic().getEncoded()),
                        "workload.b.uuid=6f3a7910-78c7-4bd5-8729-729082a7ce40",
                        "workload.b.name=vipbot01"));

        assertThrows(
                IllegalArgumentException.class,
                () -> WorkloadTrustStore.load(file));
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
