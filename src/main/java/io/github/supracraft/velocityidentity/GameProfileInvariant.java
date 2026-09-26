package io.github.supracraft.velocityidentity;

import com.velocitypowered.api.util.GameProfile;

import java.util.List;
import java.util.Objects;

public final class GameProfileInvariant {
    private GameProfileInvariant() {
    }

    public static boolean matchesExactly(GameProfile expected, GameProfile actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");

        if (!expected.getId().equals(actual.getId())
                || !expected.getName().equals(actual.getName())) {
            return false;
        }

        List<GameProfile.Property> expectedProperties = expected.getProperties();
        List<GameProfile.Property> actualProperties = actual.getProperties();
        if (expectedProperties.size() != actualProperties.size()) {
            return false;
        }

        for (int i = 0; i < expectedProperties.size(); i++) {
            GameProfile.Property left = expectedProperties.get(i);
            GameProfile.Property right = actualProperties.get(i);
            if (!left.getName().equals(right.getName())
                    || !left.getValue().equals(right.getValue())
                    || !Objects.equals(left.getSignature(), right.getSignature())) {
                return false;
            }
        }
        return true;
    }
}
