#!/usr/bin/env python3
"""Apply/remove the two evaluation intents and clear only the paired Redis DB."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path


HERE = Path(__file__).resolve().parent
TARGETS = {
    "ragent_eval_baseline": 14,
    "ragent_eval_current": 15,
}
SAFE_CONTAINER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["on", "off"])
    parser.add_argument("--database", required=True, choices=sorted(TARGETS))
    parser.add_argument("--postgres-host", default="127.0.0.1")
    parser.add_argument("--postgres-port", type=int, default=5432)
    parser.add_argument("--postgres-user", default="postgres")
    parser.add_argument(
        "--postgres-container",
        help="run psql inside this Docker container and stream the SQL over stdin",
    )
    parser.add_argument("--redis-host", default="127.0.0.1")
    parser.add_argument("--redis-port", type=int, default=6380)
    parser.add_argument(
        "--redis-container",
        help="run redis-cli inside this Docker container using RAGENT_REDIS_PASSWORD",
    )
    parser.add_argument(
        "--skip-redis-flush",
        action="store_true",
        help="not recommended; use only when the backend has never loaded intents",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    for name, value in (
        ("PostgreSQL", args.postgres_container),
        ("Redis", args.redis_container),
    ):
        if value and not SAFE_CONTAINER.fullmatch(value):
            print(f"invalid {name} container name")
            return 1
    sql = HERE / "sql" / ("enable_eval_intents.sql" if args.mode == "on" else "disable_eval_intents.sql")
    psql_connection = ["-U", args.postgres_user]
    if not args.postgres_container:
        psql_connection = [
            "-h",
            args.postgres_host,
            "-p",
            str(args.postgres_port),
            *psql_connection,
        ]
    psql_args = [
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        *psql_connection,
        "-d",
        args.database,
    ]
    if args.postgres_container:
        psql = ["docker", "exec", "-i", args.postgres_container, *psql_args]
        with sql.open("rb") as input_stream:
            completed = subprocess.run(psql, check=False, stdin=input_stream)
    else:
        completed = subprocess.run([*psql_args, "-f", str(sql)], check=False)
    if completed.returncode != 0:
        return completed.returncode

    if not args.skip_redis_flush:
        redis_db = TARGETS[args.database]
        env = os.environ.copy()
        if args.redis_container:
            command = [
                "docker",
                "exec",
                args.redis_container,
                "sh",
                "-c",
                'redis-cli --no-auth-warning -a "$RAGENT_REDIS_PASSWORD" -n "$1" FLUSHDB',
                "redis-flush",
                str(redis_db),
            ]
        else:
            command = [
                "redis-cli",
                "--no-auth-warning",
                "-h",
                args.redis_host,
                "-p",
                str(args.redis_port),
                "-n",
                str(redis_db),
                "FLUSHDB",
            ]
        completed = subprocess.run(command, check=False, env=env)
        if completed.returncode != 0:
            print("Redis flush failed; set REDISCLI_AUTH and retry before running the arm", file=sys.stderr)
            return completed.returncode
        print(f"cleared Redis DB {redis_db}")
    print("意图模式已切换；为消除进程内状态，运行下一组前重启对应后端。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
