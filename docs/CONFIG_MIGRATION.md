# Configuration compatibility and migration

The current `config-version` is `4`.

## Current behavior

- The server's `config.yml` is read, and the bundled template is layered underneath it as defaults.
- Keys absent from the file take their default value **in memory only**. The file on disk is never rewritten.
- Versions 1 through 4 are readable. A version below 1, or one newer than the plugin supports, fails activation.
- Types and ranges are validated when the runtime settings are built, or clamped into a safe range.
- If reload validation or database initialization fails, the new candidate context is closed and the existing runtime keeps running.

The implementation therefore provides no backup file creation, no comment-preserving merge, and no atomic automatic rewrite of the configuration file. Do not operate on the assumption that the file changes itself.

## v3 to v4

- Added `database.queryTimeoutSeconds`; defaults to 30 seconds when the key is absent.
- Reduced the new template's remote pool defaults to `maximumPoolSize: 2` and `minimumIdle: 0`.
- Synchronized `tick.intervalSeconds` and `afk.thresholdSeconds`, which had been missing from the distributed template.
- Removed the unimplemented `riskDetection.*`, `privacy.*`, and `logging.*` template keys.

An existing file that already specifies `maximumPoolSize: 10` and `minimumIdle: 2` is treated as a deliberate operator setting and left alone. Change it by hand to adopt the low-idle behavior. Removed keys are ignored if they remain.

## Defaults for values absent from older files

| Key | Fallback |
| --- | --- |
| `setup.enabled` | `false` |
| `database.type` | `sqlite` |
| `database.tablePrefix` | `mstats_` |
| `tick.intervalSeconds` | 5 seconds |
| `afk.thresholdSeconds` | 60 seconds |
| `database.queryTimeoutSeconds` | 30 seconds |

## Related files

`plugins/Stats/command-aliases.yml` follows the same rule: it is created once from the bundled resource and never overwritten. Canonical rules introduced by a later release must be merged manually. The alias file must declare `version: 1`.

## Requirements if automatic file migration is added later

> **Proposal, not implemented.** The following is a design requirement list for possible future work.

Automatic file migration would need all of these together:

1. A timestamped backup before any change
2. Sequential version-by-version conversion
3. A temporary file write followed by an atomic rename
4. Preservation of the original on failure
5. A diff summary that never prints sensitive values

Bukkit's `YamlConfiguration` has limited comment preservation, so a dedicated YAML library would be required if comments must survive.
