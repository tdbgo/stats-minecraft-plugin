package com.playcity.stats.collect;

import org.bukkit.Material;
import org.bukkit.Tag;

public final class MaterialGroupClassifier {
    private static final String[] GROUP_BY_ORDINAL = buildLookup();

    private MaterialGroupClassifier() {}

    public static String classify(Material material) {
        if (material == null) {
            return "other";
        }
        int ordinal = material.ordinal();
        return ordinal >= 0 && ordinal < GROUP_BY_ORDINAL.length ? GROUP_BY_ORDINAL[ordinal] : "other";
    }

    private static String[] buildLookup() {
        Material[] materials = Material.values();
        String[] groups = new String[materials.length];
        for (Material material : materials) {
            groups[material.ordinal()] = classifyUncached(material);
        }
        return groups;
    }

    private static String classifyUncached(Material material) {
        if (isContainer(material)) {
            return "container";
        }

        String name = material.name();
        if (name.endsWith("_ORE") || material == Material.ANCIENT_DEBRIS) {
            return "ore";
        }
        if (isLog(material)) {
            return "log";
        }
        if (isRedstone(material)) {
            return "redstone";
        }
        if (name.contains("RAIL")) {
            return "rail";
        }
        if (name.contains("DOOR") || name.contains("TRAPDOOR")) {
            return "door";
        }
        if (name.contains("WOOL")) {
            return "wool";
        }
        if (name.contains("CONCRETE_POWDER")) {
            return "concrete_powder";
        }
        if (name.contains("CONCRETE")) {
            return "concrete";
        }
        if (name.contains("GLAZED_TERRACOTTA")) {
            return "glazed_terracotta";
        }
        if (name.contains("TERRACOTTA")) {
            return "terracotta";
        }
        if (name.contains("STAINED_GLASS")) {
            return "stained_glass";
        }
        if (name.contains("GLASS")) {
            return "glass";
        }
        if (name.contains("PLANKS")) {
            return "planks";
        }
        if (name.contains("QUARTZ")) {
            return "quartz";
        }
        if (name.contains("PRISMARINE")) {
            return "prismarine";
        }
        if (name.contains("SANDSTONE")) {
            return "sandstone";
        }
        if (name.contains("END_STONE") || name.contains("PURPUR")) {
            return "end";
        }
        if (name.contains("NETHERRACK") || name.contains("NETHER")) {
            return "nether";
        }
        if (name.contains("BRICKS") || name.endsWith("BRICK")) {
            return "bricks";
        }
        if (name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("COBBLESTONE")
            || name.contains("ANDESITE") || name.contains("DIORITE") || name.contains("GRANITE")
            || name.contains("BLACKSTONE") || name.contains("BASALT") || name.contains("TUFF")
            || name.contains("CALCITE")) {
            return "stone";
        }
        if (name.contains("DIRT") || name.contains("SAND") || name.contains("GRAVEL") || name.contains("CLAY")
            || name.contains("MUD") || name.contains("GRASS") || name.contains("PODZOL") || name.contains("MYCELIUM")) {
            return "ground";
        }
        return "other";
    }

    private static boolean isLog(Material material) {
        try {
            return Tag.LOGS.isTagged(material);
        } catch (Throwable ignored) {
            return material.name().contains("LOG") || material.name().contains("WOOD");
        }
    }

    private static boolean isContainer(Material material) {
        String name = material.name();
        if (name.endsWith("SHULKER_BOX")) {
            return true;
        }
        return switch (material) {
            case CHEST, TRAPPED_CHEST, ENDER_CHEST, BARREL, HOPPER, DISPENSER, DROPPER,
                 FURNACE, BLAST_FURNACE, SMOKER, BREWING_STAND, CHISELED_BOOKSHELF,
                 SHULKER_BOX -> true;
            default -> false;
        };
    }

    private static boolean isRedstone(Material material) {
        String name = material.name();
        if (name.contains("REDSTONE") || name.endsWith("_BUTTON") || name.endsWith("_PRESSURE_PLATE")) {
            return true;
        }
        return switch (material) {
            case REPEATER, COMPARATOR, LEVER, PISTON, STICKY_PISTON, OBSERVER,
                 NOTE_BLOCK, TARGET, DAYLIGHT_DETECTOR, TRIPWIRE_HOOK,
                 SCULK_SENSOR, CALIBRATED_SCULK_SENSOR -> true;
            default -> false;
        };
    }
}
