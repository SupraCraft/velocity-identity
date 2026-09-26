package io.github.supracraft.velocityidentity;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WorkloadIdentityProviderTest {
    private static final AdmissionProfile WORKLOAD =
            new AdmissionProfile(
                    "workload",
                    AdmissionClass.WORKLOAD,
                    Set.of("gym"));

    @Test
    void validSignatureResolvesProvisionedIdentity() throws Exception {
        Fixture fixture = fixture();
        byte[] challenge =
                WorkloadChallengeProtocol.newChallenge(
                        new java.security.SecureRandom());
        ProviderResult result = fixture.provider.authenticate(
                request(
                        challenge,
                        response(
                                "bot-key",
                                sign(fixture.keyPair, challenge, "bot-key"))));

        assertEquals(
                ProviderDisposition.AUTHENTICATED,
                result.disposition());
        assertEquals(
                PrincipalKind.WORKLOAD,
                result.principal().kind());
        assertEquals(
                "gym/alpha/0",
                result.principal().subject());
        assertEquals(
                fixture.identity,
                result.gameIdentity());
        assertEquals(
                fixture.identity.gameUuid(),
                result.gameProfile().getId());
        assertEquals(
                fixture.identity.gameName(),
                result.gameProfile().getName());
    }

    @Test
    void badSignatureIsDenied() throws Exception {
        Fixture fixture = fixture();
        KeyPair other = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
        byte[] challenge =
                WorkloadChallengeProtocol.newChallenge(
                        new java.security.SecureRandom());

        ProviderResult result = fixture.provider.authenticate(
                request(
                        challenge,
                        response(
                                "bot-key",
                                sign(other, challenge, "bot-key"))));

        assertEquals(
                ProviderDisposition.DENIED,
                result.disposition());
    }

    @Test
    void capturedResponseCannotReplayAgainstNewChallenge()
            throws Exception {
        Fixture fixture = fixture();
        java.security.SecureRandom random =
                new java.security.SecureRandom();
        byte[] original =
                WorkloadChallengeProtocol.newChallenge(random);
        byte[] fresh =
                WorkloadChallengeProtocol.newChallenge(random);
        assertNotEquals(
                java.util.Base64.getEncoder().encodeToString(original),
                java.util.Base64.getEncoder().encodeToString(fresh));

        ProviderResult result = fixture.provider.authenticate(
                request(
                        fresh,
                        response(
                                "bot-key",
                                sign(fixture.keyPair, original, "bot-key"))));

        assertEquals(
                ProviderDisposition.DENIED,
                result.disposition());
    }

    @Test
    void unknownKeyIsDenied() throws Exception {
        Fixture fixture = fixture();
        byte[] challenge =
                WorkloadChallengeProtocol.newChallenge(
                        new java.security.SecureRandom());

        ProviderResult result = fixture.provider.authenticate(
                request(
                        challenge,
                        response(
                                "unknown",
                                sign(fixture.keyPair, challenge, "unknown"))));

        assertEquals(
                ProviderDisposition.DENIED,
                result.disposition());
    }

    @Test
    void malformedResponseIsDenied() throws Exception {
        Fixture fixture = fixture();
        byte[] challenge =
                WorkloadChallengeProtocol.newChallenge(
                        new java.security.SecureRandom());

        ProviderResult result = fixture.provider.authenticate(
                request(challenge, new byte[]{1, 4, 1}));

        assertEquals(
                ProviderDisposition.DENIED,
                result.disposition());
    }

    private static Fixture fixture() throws Exception {
        KeyPair keyPair = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
        GameIdentity identity = new GameIdentity(
                UUID.fromString(
                        "59d33a93-bbfe-4dcb-96fa-b2f6412163b9"),
                "VipBot01");
        WorkloadTrustStore store = new WorkloadTrustStore(
                "urn:vip:test",
                Map.of(
                        "bot-key",
                        new WorkloadIdentityBinding(
                                "bot-key",
                                "gym/alpha/0",
                                keyPair.getPublic(),
                                identity)));
        WorkloadTrustRuntime runtime =
                new WorkloadTrustRuntime(store);
        return new Fixture(
                keyPair,
                identity,
                new WorkloadIdentityProvider(runtime));
    }

    private static ProviderRequest request(
            byte[] challenge,
            byte[] response) {
        return new ProviderRequest(
                WorkloadIdentityProvider.MECHANISM,
                WORKLOAD,
                null,
                false,
                new WorkloadPresentation(
                        challenge,
                        response));
    }

    private static byte[] response(
            String keyId,
            byte[] signature) {
        byte[] key =
                keyId.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer
                .allocate(
                        2 + key.length
                                + WorkloadChallengeProtocol.SIGNATURE_LENGTH)
                .put((byte) 1)
                .put((byte) key.length)
                .put(key)
                .put(signature)
                .array();
    }

    @Test
    void proofIsBoundToKeyId() throws Exception {
        KeyPair pair = KeyPairGenerator
                .getInstance("Ed25519")
                .generateKeyPair();
        byte[] challenge =
                WorkloadChallengeProtocol.newChallenge(
                        new java.security.SecureRandom());

        byte[] signature = sign(
                pair,
                challenge,
                "bot-key");

        assertEquals(
                true,
                WorkloadChallengeProtocol.verify(
                        pair.getPublic(),
                        challenge,
                        "bot-key",
                        signature));
        assertEquals(
                false,
                WorkloadChallengeProtocol.verify(
                        pair.getPublic(),
                        challenge,
                        "other-key",
                        signature));
    }

    private static byte[] sign(
            KeyPair pair,
            byte[] challenge,
            String keyId) throws Exception {
        Signature signature =
                Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate());
        signature.update(
                WorkloadChallengeProtocol.signingPayload(
                        challenge,
                        keyId));
        return signature.sign();
    }

    private record Fixture(
            KeyPair keyPair,
            GameIdentity identity,
            WorkloadIdentityProvider provider) {
    }
}
