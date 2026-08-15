package com.playcity.stats.collect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandNormalizerTest {
    private final CommandNormalizer normalizer = new CommandNormalizer(new CommandAliasConfig(List.of(
        new CommandAliasRule(
            "minecraft:gamemode",
            List.of(Pattern.compile("^(gamemode|gm|gms|gmc|gma|gmsp)$")),
            true,
            List.of("mode")
        )
    )));

    @Test
    void capturesOnlyValidatedGamemodeVariants() {
        assertEquals(
            new CommandNormalizer.NormalizedCommand("minecraft:gamemode", "mode=creative"),
            normalizer.normalize(null, "/gamemode 1 SomePlayer")
        );
        assertEquals(
            new CommandNormalizer.NormalizedCommand("minecraft:gamemode", "mode=spectator"),
            normalizer.normalize(null, "/gmsp")
        );
        assertEquals(
            new CommandNormalizer.NormalizedCommand("minecraft:gamemode", ""),
            normalizer.normalize(null, "/gamemode secret-value")
        );
    }

    @Test
    void unknownCommandsKeepOnlyTheLabelAndRejectOversizedKeys() {
        assertEquals(
            new CommandNormalizer.NormalizedCommand("minecraft:warp", ""),
            normalizer.normalize(null, "/warp private-home-name")
        );
        assertTrue(normalizer.normalize(null, "/" + "a".repeat(256)).commandKey().isEmpty());
    }
}
