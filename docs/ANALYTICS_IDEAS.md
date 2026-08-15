# Derived metric ideas for an analysis layer

> **Not plugin features.** Stats 0.3.2 computes none of these. It has no dashboard, no reporting command, and no scoring engine. Everything below is a catalogue of metrics you could build **yourself**, with SQL or a BI tool, on top of the data Stats stores. Each entry is an idea, not a specification and not a shipped capability.

These build on the sessions, time buckets, and counters described in [DATA_CATALOG.md](DATA_CATALOG.md) and [SCHEMA.md](SCHEMA.md). Items explicitly marked as requiring extra data cannot be derived from the current schema alone.

## 1. Activity and retention

- **DAU / WAU / MAU** — day buckets where `playtime_sec > 0`
- **Active minutes** — from `fact_player_hour.active_minutes`, separating "connected" from "actually active"
- **AFK ratio** — `afk_sec / playtime_sec`
- **D1 / D7 / D30 retention** — return rate against the first-seen cohort date
- **New versus returning** — segments inside and outside N days of first connection
- **Streaks** — consecutive connected days, or consecutive active days excluding AFK; longest and current
- **Churn risk** — sharp decline in playtime or active minutes against the trailing 7 days

## 2. Time-of-day and peak patterns

- **Heatmap** — day of week × hour, from `fact_player_hour`
- **Peak hour** — the hour maximizing summed `playtime_sec` server-wide
- **Session pattern** — session length distribution, such as under 10 minutes, over 1 hour, over 3 hours
- **First action latency** — requires an additional first-event timestamp. The current hourly counters cannot determine the time from join to the first chat, block, or command.

## 3. Play style profiling

- **Builder index** — share of `blocks_placed_total + blocks_broken_total`
- **Explorer index** — share of `distance_m`; world variety would need per-world data Stats does not collect
- **Social index** — share of `chat_messages` and messaging or party commands
- **Staff activity** — share of administrative command families
- **Palette diversity** — count of distinct block groups used, as a proxy for build variety
- **Routine versus adventure** — repeated same-hour connections against spread-out ones

## 4. Command analysis

- **Top commands** — ranking summed from `fact_command_hour`
- **Feature adoption** — distinct users per command family over time
- **WorldEdit usage** — share of `worldedit:*` keys and their peak hours
- **Alias health** — a high share of unmatched raw labels suggests alias rules worth adding
- **Mobility patterns** — relative use of home, spawn, and teleport-request commands
- **WorldEdit material trends** — most frequent materials in `//set` and `//replace`, where the variant was captured

## 5. Safety and anomaly signals (high false-positive risk)

Treat these as prompts for human review, never as automatic enforcement.

- **Ore break ratio** — ore group breaks against total breaks
- **Container break or place spikes** — sudden growth in the container group
- **Command-volume anomaly** — sharp growth in commands per hour, suggesting activity worth reviewing. The current schema cannot resolve a 5- or 10-minute burst.
- **AFK farming** — high AFK ratio accompanied by block events, depending on server rules
- **New account bursts** — growth in first-time connections correlated with events or promotion
- **Unusual-hour activity** — concentrated activity at atypical times, which depends heavily on server demographics

## 6. Server operations reporting

- **Onboarding funnel** — first-day playtime, chat, and movement as a success proxy
- **Content impact** — week-over-week KPI comparison around an event
- **Community MVP** — top contributors by consistency and social activity; consider weighting or keeping it private to limit gaming
- **Prime-time staffing** — administrative and moderation command volume by hour

## 7. Relationships and community, without coordinates

- **Co-online overlap** — overlapping session windows as a proxy for who plays together, computable from `fact_session` alone
- **Buddy graph** — a user graph weighted by that overlap, and its clusters
- **Event crowd** — the set of users online together during a given event window
