import argparse
import csv
import sqlite3
import sys
import uuid
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path


UTC = timezone.utc


def iso_from_ms(epoch_ms: int) -> str:
    dt = datetime.fromtimestamp(epoch_ms / 1000.0, tz=UTC)
    return dt.isoformat().replace("+00:00", "Z")


def iso_from_s(epoch_s: int) -> str:
    dt = datetime.fromtimestamp(epoch_s, tz=UTC)
    return dt.isoformat().replace("+00:00", "Z")


def day_str_from_ms(epoch_ms: int) -> str:
    dt = datetime.fromtimestamp(epoch_ms / 1000.0, tz=UTC)
    return dt.date().isoformat()


def hour_ts_from_s(epoch_s: int) -> str:
    dt = datetime.fromtimestamp(epoch_s, tz=UTC).replace(minute=0, second=0, microsecond=0)
    return dt.isoformat().replace("+00:00", "Z")


def read_table_prefix(stats_config_path: Path) -> str:
    if not stats_config_path.exists():
        return "mstats_"
    for line in stats_config_path.read_text(encoding="utf-8", errors="ignore").splitlines():
        if "tablePrefix" in line and ":" in line:
            # e.g. tablePrefix: "mstats_"
            _, value = line.split(":", 1)
            value = value.strip().strip('"').strip("'")
            if value:
                return value
    return "mstats_"


def ensure_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)


def write_csv(path: Path, header: list[str], rows) -> None:
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(header)
        for row in rows:
            w.writerow(row)


@dataclass(frozen=True)
class PlanUser:
    user_id: int
    player_uuid: str
    name: str
    registered_ms: int


@dataclass(frozen=True)
class PlanSession:
    session_id: int
    user_id: int
    start_ms: int
    end_ms: int
    mob_kills: int
    deaths: int
    afk_ms: int


def load_plan(plan_db_path: Path):
    con = sqlite3.connect(plan_db_path)
    cur = con.cursor()

    cur.execute("select id, uuid, name, registered from plan_users")
    users = [PlanUser(*row) for row in cur.fetchall()]
    users_by_id = {u.user_id: u for u in users}

    cur.execute("select user_id, max(session_end) from plan_sessions group by user_id")
    last_seen_by_user = {row[0]: row[1] for row in cur.fetchall() if row[1] is not None}

    cur.execute("select id, world_name from plan_worlds")
    world_name_by_id = {row[0]: row[1] for row in cur.fetchall()}

    # session_id -> primary world_name by max time spent
    cur.execute(
        "select session_id, world_id, (survival_time + creative_time + adventure_time + spectator_time) as t from plan_world_times"
    )
    best_world = {}
    for session_id, world_id, t in cur.fetchall():
        if world_id not in world_name_by_id:
            continue
        prev = best_world.get(session_id)
        if prev is None or t > prev[0]:
            best_world[session_id] = (t, world_name_by_id[world_id])
    primary_world_by_session = {sid: name for sid, (t, name) in best_world.items()}

    cur.execute(
        "select id, user_id, session_start, session_end, mob_kills, deaths, afk_time from plan_sessions"
    )
    sessions = [PlanSession(*row) for row in cur.fetchall()]
    con.close()

    return users, users_by_id, last_seen_by_user, sessions, primary_world_by_session


def normalize_worldedit_command(raw: str) -> tuple[str, str]:
    if raw is None:
        return "", ""
    cmd = " ".join(raw.strip().lower().split())
    if not cmd:
        return "", ""

    # FAWE history often stores commands like "/paste -a", "/p", "copypaste 50"
    if cmd.startswith("/"):
        cmd = cmd[1:]
    if cmd.startswith("/"):
        cmd = cmd.lstrip("/")

    parts = cmd.split(" ")
    label = parts[0]
    args = parts[1:]

    if label.startswith("//"):
        label = label[2:]

    if label.isdigit():
        return "", ""

    alias = {
        "p": "paste",
        "paste": "paste",
        "c": "copy",
        "copy": "copy",
        "u": "undo",
        "undo": "undo",
        "s": "set",
        "set": "set",
        "re": "replace",
        "rep": "replace",
        "replace": "replace",
        "cut": "cut",
        "stack": "stack",
        "move": "move",
    }.get(label, label)

    command_key = f"worldedit:{alias}"
    variant_key = ""

    if alias in ("set", "replace") and args:
        variant_key = f"material={args[0]}"

    return command_key, variant_key


def load_fawe_edits(summary_db_paths: list[Path]):
    counts_day = defaultdict(int)  # (uuid, day, command_key, variant_key) -> count
    counts_hour = defaultdict(int)  # (uuid, hour_ts, command_key, variant_key) -> count

    for p in summary_db_paths:
        con = sqlite3.connect(p)
        cur = con.cursor()
        cur.execute("select player, time, command from _edits where player is not null and time is not null")
        for player_blob, epoch_s, raw_command in cur.fetchall():
            if raw_command is None:
                continue
            try:
                puuid = str(uuid.UUID(bytes=player_blob))
            except Exception:
                continue

            command_key, variant_key = normalize_worldedit_command(raw_command)
            if not command_key:
                continue

            day = datetime.fromtimestamp(epoch_s, tz=UTC).date().isoformat()
            hour_ts = hour_ts_from_s(epoch_s)
            counts_day[(puuid, day, command_key, variant_key)] += 1
            counts_hour[(puuid, hour_ts, command_key, variant_key)] += 1
        con.close()

    return counts_day, counts_hour


def iter_day_segments(start: datetime, end: datetime):
    cur = start
    while cur < end:
        next_day = (cur.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=1))
        seg_end = end if end < next_day else next_day
        yield cur, seg_end
        cur = seg_end


def main() -> int:
    parser = argparse.ArgumentParser(description="Export existing plugin data into Postgres-loadable files for Stats.")
    parser.add_argument("--out", type=Path, default=Path("out/pg-migration"), help="Output directory")
    parser.add_argument("--stats-config", type=Path, default=Path("plugins/Stats/config.yml"))
    parser.add_argument("--prefix", type=str, default=None, help="Table prefix override (default: from Stats config or mstats_)")

    parser.add_argument("--plan-db", type=Path, default=Path("plugins/Plan/database.db"))
    parser.add_argument("--no-plan", action="store_true", help="Skip Plan import")

    parser.add_argument("--fawe-history", type=Path, default=Path("plugins/FastAsyncWorldEdit/history"))
    parser.add_argument("--no-fawe", action="store_true", help="Skip FAWE import")

    args = parser.parse_args()

    out_dir: Path = args.out
    ensure_dir(out_dir)

    prefix = args.prefix or read_table_prefix(args.stats_config)
    if not prefix:
        prefix = "mstats_"

    # -------------------
    # Plan -> players, sessions, player_day
    # -------------------
    dim_player_rows = []
    session_rows = []
    player_day = defaultdict(lambda: {"playtime_sec": 0, "afk_sec": 0, "sessions": 0, "deaths": 0, "kills_mob": 0})

    if not args.no_plan:
        if not args.plan_db.exists():
            raise SystemExit(f"Plan DB not found: {args.plan_db}")

        users, users_by_id, last_seen_by_user, sessions, primary_world_by_session = load_plan(args.plan_db)

        for u in users:
            last_seen = last_seen_by_user.get(u.user_id, u.registered_ms)
            dim_player_rows.append(
                (u.player_uuid, iso_from_ms(u.registered_ms), iso_from_ms(last_seen), u.name)
            )

        for s in sessions:
            u = users_by_id.get(s.user_id)
            if not u:
                continue

            start_dt = datetime.fromtimestamp(s.start_ms / 1000.0, tz=UTC)
            end_dt = datetime.fromtimestamp(s.end_ms / 1000.0, tz=UTC)
            if end_dt <= start_dt:
                continue

            duration_sec = int((end_dt - start_dt).total_seconds())
            afk_sec_total = int(s.afk_ms / 1000) if s.afk_ms else 0
            world = primary_world_by_session.get(s.session_id)

            session_rows.append(
                (
                    u.player_uuid,
                    start_dt.isoformat().replace("+00:00", "Z"),
                    end_dt.isoformat().replace("+00:00", "Z"),
                    duration_sec,
                    afk_sec_total,
                    world or "",
                    world or "",
                )
            )

            # day segments for playtime/afk
            for seg_start, seg_end in iter_day_segments(start_dt, end_dt):
                day = seg_start.date().isoformat()
                seg_sec = int((seg_end - seg_start).total_seconds())
                key = (u.player_uuid, day)
                player_day[key]["playtime_sec"] += seg_sec
                if duration_sec > 0 and afk_sec_total > 0:
                    player_day[key]["afk_sec"] += int(round(afk_sec_total * (seg_sec / duration_sec)))

            # session count + deaths/kills are attributed to session start day (simple, deterministic)
            start_day = start_dt.date().isoformat()
            k = (u.player_uuid, start_day)
            player_day[k]["sessions"] += 1
            player_day[k]["deaths"] += int(s.deaths or 0)
            player_day[k]["kills_mob"] += int(s.mob_kills or 0)

    # -------------------
    # FAWE -> commands (worldedit family)
    # -------------------
    command_day_rows = []
    command_hour_rows = []

    if not args.no_fawe:
        if args.fawe_history.exists():
            summary_paths = sorted(args.fawe_history.rglob("summary.db"))
        else:
            summary_paths = []

        if summary_paths:
            counts_day, counts_hour = load_fawe_edits(summary_paths)

            for (puuid, day, command_key, variant_key), count in counts_day.items():
                command_day_rows.append((puuid, day, command_key, variant_key, count))

            for (puuid, hour_ts, command_key, variant_key), count in counts_hour.items():
                command_hour_rows.append((puuid, hour_ts, command_key, variant_key, count))

    # -------------------
    # Write CSVs
    # -------------------
    write_csv(out_dir / "dim_player.csv", ["player_uuid", "first_seen_at", "last_seen_at", "last_known_name"], dim_player_rows)
    write_csv(
        out_dir / "fact_session.csv",
        ["player_uuid", "join_at", "quit_at", "duration_sec", "afk_sec", "join_world", "quit_world"],
        session_rows,
    )

    player_day_rows = []
    for (puuid, day), v in player_day.items():
        player_day_rows.append(
            (puuid, day, v["playtime_sec"], v["sessions"], v["deaths"], 0, v["kills_mob"])
        )
    write_csv(
        out_dir / "fact_player_day.csv",
        ["player_uuid", "day", "playtime_sec", "sessions", "deaths", "kills_pvp", "kills_mob"],
        sorted(player_day_rows, key=lambda r: (r[0], r[1])),
    )

    write_csv(
        out_dir / "fact_command_day_raw.csv",
        ["player_uuid", "day", "command_key", "variant_key", "count"],
        sorted(command_day_rows, key=lambda r: (r[2], r[0], r[1])),
    )
    write_csv(
        out_dir / "fact_command_hour_raw.csv",
        ["player_uuid", "hour_ts", "command_key", "variant_key", "count"],
        sorted(command_hour_rows, key=lambda r: (r[2], r[0], r[1])),
    )

    # -------------------
    # Write Postgres load.sql
    # -------------------
    load_sql = out_dir / "load.sql"
    with load_sql.open("w", encoding="utf-8") as f:
        f.write("\\set ON_ERROR_STOP on\n")
        f.write("BEGIN;\n")
        f.write("SET TIME ZONE 'UTC';\n\n")

        f.write("-- 1) Players\n")
        f.write("CREATE TEMP TABLE tmp_dim_player (\n")
        f.write("  player_uuid uuid,\n")
        f.write("  first_seen_at timestamptz,\n")
        f.write("  last_seen_at timestamptz,\n")
        f.write("  last_known_name text\n")
        f.write(");\n")
        f.write("\\copy tmp_dim_player(player_uuid, first_seen_at, last_seen_at, last_known_name) FROM 'dim_player.csv' WITH (FORMAT csv, HEADER true);\n")
        f.write(
            f"INSERT INTO {prefix}dim_player(player_uuid, first_seen_at, last_seen_at, last_known_name)\n"
            "SELECT player_uuid, first_seen_at, last_seen_at, NULLIF(last_known_name, '')\n"
            "FROM tmp_dim_player\n"
            "ON CONFLICT (player_uuid) DO UPDATE SET\n"
            "  first_seen_at = LEAST(EXCLUDED.first_seen_at, " + f"{prefix}dim_player.first_seen_at),\n"
            "  last_seen_at  = GREATEST(EXCLUDED.last_seen_at, " + f"{prefix}dim_player.last_seen_at),\n"
            "  last_known_name = COALESCE(EXCLUDED.last_known_name, " + f"{prefix}dim_player.last_known_name);\n\n"
        )

        f.write("-- 2) Sessions (best-effort insert; assumes empty target or non-overlapping data)\n")
        f.write("CREATE TEMP TABLE tmp_fact_session (\n")
        f.write("  player_uuid uuid,\n")
        f.write("  join_at timestamptz,\n")
        f.write("  quit_at timestamptz,\n")
        f.write("  duration_sec integer,\n")
        f.write("  afk_sec integer,\n")
        f.write("  join_world text,\n")
        f.write("  quit_world text\n")
        f.write(");\n")
        f.write("\\copy tmp_fact_session(player_uuid, join_at, quit_at, duration_sec, afk_sec, join_world, quit_world) FROM 'fact_session.csv' WITH (FORMAT csv, HEADER true);\n")
        f.write(
            f"INSERT INTO {prefix}fact_session(player_uuid, join_at, quit_at, duration_sec, afk_sec, join_world, quit_world)\n"
            "SELECT player_uuid, join_at, quit_at, duration_sec, afk_sec, NULLIF(join_world, ''), NULLIF(quit_world, '')\n"
            "FROM tmp_fact_session;\n\n"
        )

        f.write("-- 3) Player day\n")
        f.write("CREATE TEMP TABLE tmp_fact_player_day (\n")
        f.write("  player_uuid uuid,\n")
        f.write("  day date,\n")
        f.write("  playtime_sec integer,\n")
        f.write("  sessions integer,\n")
        f.write("  deaths integer,\n")
        f.write("  kills_pvp integer,\n")
        f.write("  kills_mob integer\n")
        f.write(");\n")
        f.write("\\copy tmp_fact_player_day(player_uuid, day, playtime_sec, sessions, deaths, kills_pvp, kills_mob) FROM 'fact_player_day.csv' WITH (FORMAT csv, HEADER true);\n")
        f.write(
            f"INSERT INTO {prefix}fact_player_day(player_uuid, day, playtime_sec, sessions, deaths, kills_pvp, kills_mob)\n"
            "SELECT player_uuid, day, playtime_sec, sessions, deaths, kills_pvp, kills_mob\n"
            "FROM tmp_fact_player_day\n"
            "ON CONFLICT (player_uuid, day) DO UPDATE SET\n"
            "  playtime_sec = " + f"{prefix}fact_player_day.playtime_sec + EXCLUDED.playtime_sec,\n"
            "  sessions = " + f"{prefix}fact_player_day.sessions + EXCLUDED.sessions,\n"
            "  deaths = " + f"{prefix}fact_player_day.deaths + EXCLUDED.deaths,\n"
            "  kills_pvp = " + f"{prefix}fact_player_day.kills_pvp + EXCLUDED.kills_pvp,\n"
            "  kills_mob = " + f"{prefix}fact_player_day.kills_mob + EXCLUDED.kills_mob;\n\n"
        )

        f.write("-- 4) Commands (FAWE history)\n")
        f.write("CREATE TEMP TABLE tmp_command_day (\n")
        f.write("  player_uuid uuid,\n")
        f.write("  day date,\n")
        f.write("  command_key text,\n")
        f.write("  variant_key text,\n")
        f.write("  count integer\n")
        f.write(");\n")
        f.write("\\copy tmp_command_day(player_uuid, day, command_key, variant_key, count) FROM 'fact_command_day_raw.csv' WITH (FORMAT csv, HEADER true);\n\n")

        f.write("CREATE TEMP TABLE tmp_command_hour (\n")
        f.write("  player_uuid uuid,\n")
        f.write("  hour_ts timestamptz,\n")
        f.write("  command_key text,\n")
        f.write("  variant_key text,\n")
        f.write("  count integer\n")
        f.write(");\n")
        f.write("\\copy tmp_command_hour(player_uuid, hour_ts, command_key, variant_key, count) FROM 'fact_command_hour_raw.csv' WITH (FORMAT csv, HEADER true);\n\n")

        f.write("-- Insert command definitions\n")
        f.write(
            f"INSERT INTO {prefix}dim_command(command_key, family)\n"
            "SELECT DISTINCT command_key,\n"
            "  CASE WHEN command_key LIKE 'worldedit:%' THEN 'worldedit' ELSE NULL END\n"
            "FROM (\n"
            "  SELECT command_key FROM tmp_command_day\n"
            "  UNION ALL\n"
            "  SELECT command_key FROM tmp_command_hour\n"
            ") t\n"
            "WHERE command_key IS NOT NULL AND command_key <> ''\n"
            "ON CONFLICT (command_key) DO UPDATE SET family = COALESCE(" + f"{prefix}dim_command.family, EXCLUDED.family);\n\n"
        )

        f.write("-- Insert command variants\n")
        f.write(
            f"INSERT INTO {prefix}dim_command_variant(command_id, variant_key)\n"
            f"SELECT c.command_id, COALESCE(NULLIF(v.variant_key, ''), '')\n"
            "FROM (\n"
            "  SELECT DISTINCT command_key, variant_key FROM tmp_command_day\n"
            "  UNION\n"
            "  SELECT DISTINCT command_key, variant_key FROM tmp_command_hour\n"
            ") v\n"
            f"JOIN {prefix}dim_command c ON c.command_key = v.command_key\n"
            "ON CONFLICT (command_id, variant_key) DO NOTHING;\n\n"
        )

        f.write("-- Insert facts (merge counts)\n")
        f.write(
            f"INSERT INTO {prefix}fact_command_day(player_uuid, day, variant_id, count)\n"
            "SELECT t.player_uuid, t.day, v.variant_id, t.count\n"
            f"FROM tmp_command_day t\n"
            f"JOIN {prefix}dim_command c ON c.command_key = t.command_key\n"
            f"JOIN {prefix}dim_command_variant v ON v.command_id = c.command_id AND v.variant_key = COALESCE(NULLIF(t.variant_key, ''), '')\n"
            "ON CONFLICT (player_uuid, day, variant_id) DO UPDATE SET count = "
            + f"{prefix}fact_command_day.count + EXCLUDED.count;\n\n"
        )

        f.write(
            f"INSERT INTO {prefix}fact_command_hour(player_uuid, hour_ts, variant_id, count)\n"
            "SELECT t.player_uuid, t.hour_ts, v.variant_id, t.count\n"
            f"FROM tmp_command_hour t\n"
            f"JOIN {prefix}dim_command c ON c.command_key = t.command_key\n"
            f"JOIN {prefix}dim_command_variant v ON v.command_id = c.command_id AND v.variant_key = COALESCE(NULLIF(t.variant_key, ''), '')\n"
            "ON CONFLICT (player_uuid, hour_ts, variant_id) DO UPDATE SET count = "
            + f"{prefix}fact_command_hour.count + EXCLUDED.count;\n\n"
        )

        f.write("COMMIT;\n")

    report = out_dir / "report.txt"
    report.write_text(
        "\n".join(
            [
                f"prefix={prefix}",
                f"dim_player_rows={len(dim_player_rows)}",
                f"fact_session_rows={len(session_rows)}",
                f"fact_player_day_rows={len(player_day_rows)}",
                f"fact_command_day_rows={len(command_day_rows)}",
                f"fact_command_hour_rows={len(command_hour_rows)}",
            ]
        )
        + "\n",
        encoding="utf-8",
    )

    print(f"Wrote: {out_dir}")
    print(report.read_text(encoding="utf-8").strip())
    print("\nNext: run Postgres schema then psql -f load.sql from that directory.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
