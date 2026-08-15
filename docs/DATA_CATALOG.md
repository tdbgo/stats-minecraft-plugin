# Data catalog

The exact collection scope implemented in Stats 0.3.2. The storage unit is session, hour, and day aggregates. No raw event stream is stored.

## Identifiers

Only `player_uuid` and a convenience `last_known_name` are used. No other identity field is recorded.

## Never collected

- Precise coordinates and movement paths.
- Chat message bodies, raw command lines, and full argument lists.
- IP addresses, client identification, and inventory snapshots.
- Per-block event timestamps and positions, and the original `Material` name of any block.

The `mstats_fact_session` table carries `ip_hash`, `client_brand`, and `locale` columns for schema compatibility. This collector does not write them; they stay null.

## Implemented

| Area | Metrics and fields | Storage unit | Cost and accuracy |
| --- | --- | --- | --- |
| Player | first and last seen, last known name | player | Driven by join, quit, and tick; low volume |
| Session | join, quit, duration, AFK seconds, join world, quit world | session | One row per connection; a reload can split a visit into two rows |
| Time | playtime, AFK seconds | hour; playtime also stored per day | Split to the second from the 5-second default tick |
| Activity | active minutes | player × hour | Bitset of whether an activity event occurred in each minute, 0–60 |
| Chat | message count, character count | player × hour | Bodies are never serialized or stored |
| Commands | canonical command and variant execution counts | player × hour and day | No raw arguments; only allow-listed variants |
| Blocks | place and break totals | player × hour | One counter increment per event |
| Block groups | place and break counts per group | player × day × group | Reduced to a fixed group instead of the material |
| Deaths | death count, and count per cause | player × day and cause | Bukkit damage cause key, lowercased, or `unknown` |
| Kills | PvP and mob kill counts | player × day | Entity death events with a player killer |
| Travel | ordinary movement distance | player × hour | Sum of straight lines between tick position samples; coordinates discarded immediately |
| Teleports | count and same-world distance | player × hour and day | Separate from ordinary travel; cross-world distance is 0 |

`playtime_sec` is total connected time and `afk_sec` is the AFK portion inside it. They overlap; do not add them together as if they were exclusive.

## Activity events

Active minutes and the last-activity timestamp are updated by these signals:

- Movement that changes the block coordinate
- Chat and command execution
- Block place and break
- Teleport

Head rotation alone does not count as activity. Inventory clicks are not currently an activity signal.

Repeated events from the same player within the same minute are filtered in player state, so the active-minute bitset is updated at most once per minute per player.

## Block groups

Group keys are limited to this fixed set:

`container`, `ore`, `log`, `redstone`, `rail`, `door`, `wool`, `concrete`, `concrete_powder`, `terracotta`, `glazed_terracotta`, `glass`, `stained_glass`, `planks`, `bricks`, `quartz`, `prismarine`, `sandstone`, `end`, `nether`, `stone`, `ground`, `other`

Classification is computed once per `Material` at plugin load, so the event path performs an array lookup rather than repeated string matching. More specific groups are matched before general ones — `SANDSTONE`, `END_STONE`, and the `NETHER` family are resolved before plain `STONE` or `BRICKS`.

## Approximation and data volume

- Position is compared in memory every 5 seconds by default, so back-and-forth and curved movement measures shorter than the real path. Lowering `tick.intervalSeconds` to 1 improves accuracy and increases tick work.
- A single movement sample counts only when it is greater than 0 and strictly less than 1000 metres. A sample of exactly 1000 metres is excluded, as is any movement between worlds.
- One integer distance per active hour replaces the raw coordinate stream, so the volume difference against a position log is very large.
- Blocks produce hourly totals and daily group counters, not one row per event. Heavy mining still yields one row per player, day, group, and action.
- Commands are merged the same way: one row per player, hour, and variant, regardless of execution count.

## Not implemented

- Item pickup, drop, craft, and inventory changes
- Per-world dwell time and dimension keys
- Damage dealt and taken
- Server health such as TPS and MSPT
- Risk detection and real-time alerting
- Opt-in chat or IP collection
- A raw event stream or continuous per-event write-ahead log

Adding any of these requires defining its cost, cardinality, retention policy, and schema at the same time.

The local flush spool is implemented, but it is not a new collection category. It temporarily holds only the aggregate snapshots listed above, and each file is deleted once the database has accepted it.
