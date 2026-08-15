package com.playcity.stats.collect;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaterialGroupClassifierTest {
    @Test
    void classifiesSpecificFamiliesBeforeGenericNames() {
        assertEquals("sandstone", MaterialGroupClassifier.classify(Material.RED_SANDSTONE));
        assertEquals("end", MaterialGroupClassifier.classify(Material.END_STONE_BRICKS));
        assertEquals("nether", MaterialGroupClassifier.classify(Material.RED_NETHER_BRICKS));
        assertEquals("container", MaterialGroupClassifier.classify(Material.ENDER_CHEST));
        assertEquals("redstone", MaterialGroupClassifier.classify(Material.SPRUCE_BUTTON));
    }

    @Test
    void oreMatchingDoesNotUseAnUnboundedSubstring() {
        assertEquals("ore", MaterialGroupClassifier.classify(Material.DEEPSLATE_DIAMOND_ORE));
        assertEquals("other", MaterialGroupClassifier.classify(Material.SPORE_BLOSSOM));
    }
}
