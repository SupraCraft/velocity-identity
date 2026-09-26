package io.github.supracraft.velocityidentity;

import java.util.Objects;

public record WorkloadPresentation(
        byte[] challenge,
        byte[] response) {

    public WorkloadPresentation {
        challenge = Objects.requireNonNull(
                challenge,
                "challenge").clone();
        response = Objects.requireNonNull(
                response,
                "response").clone();
    }

    @Override
    public byte[] challenge() {
        return challenge.clone();
    }

    @Override
    public byte[] response() {
        return response.clone();
    }
}
