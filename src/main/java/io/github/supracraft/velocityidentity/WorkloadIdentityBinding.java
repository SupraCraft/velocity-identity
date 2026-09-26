package io.github.supracraft.velocityidentity;

import java.security.PublicKey;
import java.util.Objects;
import java.util.regex.Pattern;

public record WorkloadIdentityBinding(
        String keyId,
        String subject,
        PublicKey publicKey,
        GameIdentity gameIdentity) {

    private static final Pattern KEY_ID =
            Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public WorkloadIdentityBinding {
        keyId = Objects.requireNonNull(keyId, "keyId").trim();
        subject = Objects.requireNonNull(subject, "subject").trim();
        publicKey = Objects.requireNonNull(publicKey, "publicKey");
        gameIdentity = Objects.requireNonNull(gameIdentity, "gameIdentity");
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException(
                    "keyId must match [A-Za-z0-9_-]{1,64}");
        }
        if (subject.isEmpty()) {
            throw new IllegalArgumentException(
                    "subject must not be empty");
        }
    }
}
