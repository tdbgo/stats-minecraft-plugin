# Command normalization and argument policy

The goal is to merge aliases of the same feature into one low-cardinality key while storing neither the raw command line nor sensitive arguments.

## What is collected

The command line is observed before execution, but only these results reach the database:

- `command_key` — the canonical label, for example `minecraft:gamemode` or `worldedit:replace`
- `variant_key` — an allow-listed low-cardinality value, for example `mode=creative`
- Execution counts per hour and per day bucket

The raw command line, the full argument list, target player names, message bodies, and coordinates are kept in neither the snapshot nor the database.

## Normalization order

1. Trim leading and trailing whitespace.
2. Remove a single leading `/` from ordinary commands. A WorldEdit-style `//` prefix is preserved.
3. Lowercase, then split on whitespace.
4. Compare the first token, the label, against the regular expressions in `command-aliases.yml` in file order.
5. On a match, use the rule's `canonical`. With no match, prefix a namespace-less label with `minecraft:`.
6. If the label or the canonical key exceeds 255 characters, collect nothing for that execution.
7. Convert only the `safe_args` the matched rule permits, using the limited parsers built into the plugin.

The bundled resource and the operational reference copy under `config/` are kept identical. The file first copied to `plugins/Stats/command-aliases.yml` is never overwritten afterwards, so canonical rules added in a later release must be merged by hand.

## Supported safe_args

| Key | Accepted input | Resulting variant |
| --- | --- | --- |
| `mode` | `survival`/`creative`/`adventure`/`spectator`, their short forms, and the digits `0`–`3` | `mode=<name>` |
| `material` | The first argument only, and only when Bukkit's `Material.matchMaterial` resolves it exactly | `material=<name>` |
| `target_kind` | Any first argument | `target_kind=self` if it matches the sender's own name, otherwise `target_kind=other`. The name itself is never stored. |
| `home_kind` | Presence or absence of an argument | `home_kind=default` with no argument, `home_kind=other` with one |

A rule may also set `mode_from_label_suffix: true`, which derives the mode from the label itself for the `gmc`, `gms`, `gma`, and `gmsp` shorthands.

Arbitrary `safe_args` names in the configuration are ignored and store nothing. When a rule lists several `safe_args`, the last one that resolves successfully determines the variant, so a rule normally lists only one.

## Examples

| Input | `command_key` | `variant_key` | Discarded |
| --- | --- | --- | --- |
| `/gmc` | `minecraft:gamemode` | `mode=creative` | — |
| `/gamemode 1 SomePlayer` | `minecraft:gamemode` | `mode=creative` | player name |
| `//set stone` | `worldedit:set` | `material=stone` | — |
| `/msg Alice hello` | `essentials:msg` | `target_kind=other` | target and message body |
| `/tp 10 64 10` | `essentials:tp` | *(empty)* | coordinates |
| `/warp private-home` (unregistered) | `minecraft:warp` | *(empty)* | argument |

## Operational notes

- The first matching rule wins, so put specific patterns before general ones.
- Patterns are trusted administrator configuration, but labels are capped at 255 characters. Avoid needlessly complex regular expressions.
- The alias file must declare `version: 1`. Any other value is rejected.
- A syntax error in the alias file makes the reload fail, and the previously working runtime keeps running.
