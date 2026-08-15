package com.playcity.stats.collect;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class CommandNormalizer {
    private static final int MAX_COMMAND_KEY_LENGTH = 255;
    private final CommandAliasConfig aliasConfig;

    public CommandNormalizer(CommandAliasConfig aliasConfig) {
        this.aliasConfig = aliasConfig;
    }

    public NormalizedCommand normalize(Player sender, String rawCommandLine) {
        if (rawCommandLine == null || rawCommandLine.isBlank()) {
            return NormalizedCommand.empty();
        }

        String input = rawCommandLine.trim();

        // Preserve WorldEdit style "//*" commands.
        boolean isDoubleSlash = input.startsWith("//");
        if (!isDoubleSlash && input.startsWith("/")) {
            input = input.substring(1);
        }

        input = input.trim().toLowerCase(Locale.ROOT);
        if (input.isEmpty()) {
            return NormalizedCommand.empty();
        }

        String[] parts = input.split("\\s+");
        String label = parts[0];
        if (label.length() > MAX_COMMAND_KEY_LENGTH) {
            return NormalizedCommand.empty();
        }
        String[] args = new String[Math.max(0, parts.length - 1)];
        if (args.length > 0) {
            System.arraycopy(parts, 1, args, 0, args.length);
        }

        String canonical = null;
        String variantKey = "";
        CommandAliasRule matchedRule = null;

        for (CommandAliasRule rule : aliasConfig.rules()) {
            for (var pattern : rule.match()) {
                if (pattern.matcher(label).matches()) {
                    canonical = rule.canonical();
                    matchedRule = rule;
                    break;
                }
            }
            if (canonical != null) {
                break;
            }
        }

        if (canonical == null) {
            // Default canonicalization: namespace-less label, but keep the label as-is.
            canonical = label.contains(":") ? label : "minecraft:" + label;
        }
        if (canonical.length() > MAX_COMMAND_KEY_LENGTH) {
            return NormalizedCommand.empty();
        }

        if (matchedRule != null) {
            if (matchedRule.modeFromLabelSuffix() && canonical.equals("minecraft:gamemode")) {
                variantKey = modeVariantFromLabel(label);
            }
            variantKey = variantKeyFromSafeArgs(sender, matchedRule.safeArgs(), args, variantKey);
        }

        return new NormalizedCommand(canonical, variantKey);
    }

    private static String modeVariantFromLabel(String label) {
        return switch (label) {
            case "gmc" -> "mode=creative";
            case "gms" -> "mode=survival";
            case "gma" -> "mode=adventure";
            case "gmsp" -> "mode=spectator";
            default -> "";
        };
    }

    private static String variantKeyFromSafeArgs(Player sender, List<String> safeArgs, String[] args, String existing) {
        if (safeArgs == null || safeArgs.isEmpty()) {
            return existing == null ? "" : existing;
        }

        String variant = existing == null ? "" : existing;
        for (String key : safeArgs) {
            if (key.equals("material")) {
                if (args.length >= 1) {
                    Material material = Material.matchMaterial(args[0], false);
                    if (material != null) {
                        variant = "material=" + material.name().toLowerCase(Locale.ROOT);
                    }
                }
            } else if (key.equals("mode")) {
                if (args.length >= 1) {
                    variant = modeVariantFromArgument(args[0]);
                }
            } else if (key.equals("target_kind")) {
                if (args.length >= 1 && sender != null) {
                    String target = args[0];
                    if (target.equalsIgnoreCase(sender.getName())) {
                        variant = "target_kind=self";
                    } else {
                        variant = "target_kind=other";
                    }
                }
            } else if (key.equals("home_kind")) {
                if (args.length == 0) {
                    variant = "home_kind=default";
                } else {
                    variant = "home_kind=other";
                }
            }
        }
        return variant;
    }

    private static String modeVariantFromArgument(String argument) {
        return switch (argument.toLowerCase(Locale.ROOT)) {
            case "0", "survival", "s" -> "mode=survival";
            case "1", "creative", "c" -> "mode=creative";
            case "2", "adventure", "a" -> "mode=adventure";
            case "3", "spectator", "sp" -> "mode=spectator";
            default -> "";
        };
    }

    public record NormalizedCommand(String commandKey, String variantKey) {
        public static NormalizedCommand empty() {
            return new NormalizedCommand("", "");
        }
    }
}
