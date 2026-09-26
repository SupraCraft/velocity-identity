package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Arrays;

public final class WorkloadChallengeProtocol {
    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create(
                    "supracraft",
                    "vip_auth");
    public static final int CHALLENGE_LENGTH = 36;
    public static final int SIGNATURE_LENGTH = 64;
    private static final byte[] MAGIC =
            new byte[]{'V', 'I', 'P', '1'};

    private WorkloadChallengeProtocol() {
    }

    public static byte[] newChallenge(SecureRandom random) {
        byte[] challenge = new byte[CHALLENGE_LENGTH];
        System.arraycopy(MAGIC, 0, challenge, 0, MAGIC.length);
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        System.arraycopy(
                nonce,
                0,
                challenge,
                MAGIC.length,
                nonce.length);
        return challenge;
    }

    public static WorkloadResponse parseResponse(byte[] response) {
        if (response == null || response.length < 2 + SIGNATURE_LENGTH) {
            throw new IllegalArgumentException(
                    "workload response is truncated");
        }
        ByteBuffer buffer = ByteBuffer.wrap(response);
        int version = Byte.toUnsignedInt(buffer.get());
        if (version != 1) {
            throw new IllegalArgumentException(
                    "unsupported workload response version");
        }
        int keyLength = Byte.toUnsignedInt(buffer.get());
        if (keyLength < 1 || keyLength > 64
                || response.length
                != 2 + keyLength + SIGNATURE_LENGTH) {
            throw new IllegalArgumentException(
                    "invalid workload response length");
        }

        byte[] keyBytes = new byte[keyLength];
        buffer.get(keyBytes);
        String keyId = new String(
                keyBytes,
                StandardCharsets.US_ASCII);
        byte[] signature = new byte[SIGNATURE_LENGTH];
        buffer.get(signature);
        return new WorkloadResponse(keyId, signature);
    }

    public static boolean verify(
            PublicKey publicKey,
            byte[] challenge,
            byte[] signature)
            throws GeneralSecurityException {
        if (challenge == null
                || challenge.length != CHALLENGE_LENGTH
                || !Arrays.equals(
                MAGIC,
                Arrays.copyOf(challenge, MAGIC.length))) {
            return false;
        }
        if (signature == null
                || signature.length != SIGNATURE_LENGTH) {
            return false;
        }

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(challenge);
        return verifier.verify(signature);
    }

    public record WorkloadResponse(
            String keyId,
            byte[] signature) {
        public WorkloadResponse {
            keyId = keyId == null ? "" : keyId.trim();
            signature = signature == null
                    ? new byte[0]
                    : signature.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }
}
