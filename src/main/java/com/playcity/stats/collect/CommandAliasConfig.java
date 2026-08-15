package com.playcity.stats.collect;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class CommandAliasConfig {
    private final List<CommandAliasRule> rules;

    public CommandAliasConfig(List<CommandAliasRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<CommandAliasRule> rules() {
        return rules;
    }

    @SuppressWarnings("unchecked")
    public static CommandAliasConfig load(File file) throws IOException, InvalidConfigurationException {
        if (!file.exists()) {
            return new CommandAliasConfig(List.of());
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        int version = yaml.getInt("version", -1);
        if (version != 1) {
            throw new InvalidConfigurationException("Unsupported command alias config version: " + version);
        }
        List<Map<?, ?>> ruleMaps = yaml.getMapList("rules");
        List<CommandAliasRule> rules = new ArrayList<>();
        for (Map<?, ?> ruleMap : ruleMaps) {
            Object canonicalObj = ruleMap.get("canonical");
            if (!(canonicalObj instanceof String canonical) || canonical.isBlank()) {
                continue;
            }

            List<String> matchStrings = new ArrayList<>();
            Object matchObj = ruleMap.get("match");
            if (matchObj instanceof List<?> list) {
                for (Object v : list) {
                    if (v instanceof String s && !s.isBlank()) {
                        matchStrings.add(s);
                    }
                }
            }
            if (matchStrings.isEmpty()) {
                continue;
            }

            boolean modeFromSuffix = false;
            List<String> safeArgs = Collections.emptyList();
            Object captureObj = ruleMap.get("capture");
            if (captureObj instanceof Map<?, ?> captureMap) {
                Object modeObj = captureMap.get("mode_from_label_suffix");
                if (modeObj instanceof Boolean b) {
                    modeFromSuffix = b;
                }
                Object safeArgsObj = captureMap.get("safe_args");
                if (safeArgsObj instanceof List<?> list) {
                    List<String> parsed = new ArrayList<>();
                    for (Object v : list) {
                        if (v instanceof String s && !s.isBlank()) {
                            parsed.add(s);
                        }
                    }
                    safeArgs = List.copyOf(parsed);
                }
            }

            List<Pattern> patterns = new ArrayList<>();
            for (String ms : matchStrings) {
                patterns.add(Pattern.compile(ms));
            }

            rules.add(new CommandAliasRule(canonical, List.copyOf(patterns), modeFromSuffix, safeArgs));
        }

        return new CommandAliasConfig(List.copyOf(rules));
    }
}
