# PostgreSQL backfill guide (manual workflow)

> **Not a plugin feature.** Stats 0.3.2 performs no backfill and has no import command. This document describes a manual procedure run outside the server, using the conversion scripts in the repository's `scripts/` directory against database files you supply. Nothing here happens automatically, and the plugin is not involved in any step except the schema it defines.
>
> Read [IMPORT_EXISTING_DATA.md](IMPORT_EXISTING_DATA.md) first for what can and cannot be recovered from each source.

This describes converting existing plugin data into the Stats PostgreSQL schema (`mstats_*`) and loading it.

## 1. What is backfilled

**From a Plan (Player Analytics) database**

- `mstats_dim_player`
- `mstats_fact_session` — the world is inferred as the world with the greatest dwell time within the session
- `mstats_fact_player_day` — sessions split and rolled up per day

**From FastAsyncWorldEdit history summaries**

- `mstats_dim_command`, `mstats_dim_command_variant`
- `mstats_fact_command_day`, `mstats_fact_command_hour` — WorldEdit-family commands

## 2. Preparation

- Create the Stats schema in PostgreSQL from `sql/postgres/schema.sql`.
- Confirm the table prefix in `plugins/Stats/config.yml` under `database.tablePrefix`; the default is `mstats_`. The generated SQL must match the prefix the plugin uses, or the plugin will write to a different set of tables.
- Take a backup of the target database. The load is additive and is not designed to be undone.

## 3. Generate the conversion files

Run one of the conversion scripts from the repository root, pointing it at an output directory:

```text
python scripts/export_existing_to_pg.py --out out/pg-migration
```

or the PowerShell equivalent:

```text
powershell -File scripts/export_existing_to_pg.ps1
```

Output:

- `out/pg-migration/load.sql`
- `out/pg-migration/*.csv`
- `out/pg-migration/report.txt`

Review `report.txt` before loading anything.

## 4. Load into PostgreSQL

Running `psql` from inside the output directory is simplest, because `load.sql` uses relative `\copy` paths:

```text
cd out/pg-migration
psql "postgresql://USER:PASSWORD@HOST:5432/DBNAME" -f load.sql
```

Use a connection string appropriate to your environment, and avoid placing credentials in shell history where that matters.

## 5. Cautions

- `load.sql` uses merge (additive) upserts for `fact_player_day` and `fact_command_*`. **Loading the same data twice accumulates the values.** Load once, and verify before repeating.
- `fact_session` is a best-effort insert. It is safest when the target table is empty.
- Backfilled rows are not distinguishable from collected rows afterwards. Record what you loaded and for which date range.
- Run the backfill before or during a maintenance window rather than against a busy production database.
