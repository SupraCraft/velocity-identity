package io.github.supracraft.velocityidentity;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class GameNames {
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private GameNames() {}

    public static String requireValid(String name) {
        Objects.requireNonNull(name, "name");
        if (!VALID.matcher(name).matches()) {
            throw new IllegalArgumentException("Minecraft game name must match [A-Za-z0-9_]{1,16}");
        }
        return name;
    }

    public static String guest(UUID uuid) {
        String compact = uuid.toString().replace("-", "");
        return "Guest_" + compact.substring(0, 10);
    }
}
