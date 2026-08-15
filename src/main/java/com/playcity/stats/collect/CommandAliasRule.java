package com.playcity.stats.collect;

import java.util.List;
import java.util.regex.Pattern;

public record CommandAliasRule(
    String canonical,
    List<Pattern> match,
    boolean modeFromLabelSuffix,
    List<String> safeArgs
) {}

