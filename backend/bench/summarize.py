"""Summarises a token-version-cache.sh run as Markdown: medians per build, endpoint and
concurrency, each compared with the build from before the cache."""
import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

run = Path(sys.argv[1])
rows = list(csv.DictReader(open(run / "results.tsv"), delimiter="\t"))
groups = defaultdict(list)
for row in rows:
    groups[(row["endpoint"], int(row["concurrency"]), row["variant"])].append(row)


def median(group, field):
    return statistics.median(float(row[field]) for row in group)


def spread(group, field):
    values = [float(row[field]) for row in group]
    return f"{min(values):.2f}–{max(values):.2f}"


failed = sum(int(row["failed"]) for row in rows)
reps = len({row["rep"] for row in rows})
print(f"{len(rows)} runs of 5,000 requests, {reps} rounds, {failed} failed or non-2xx responses\n")

load = run / "load.tsv"
if load.exists():
    print("Load average (1, 5, 15 minutes) on a machine with "
          + next(csv.reader(open(load), delimiter="\t"))[2] + " cores:")
    for moment, averages, _ in csv.reader(open(load), delimiter="\t"):
        when = "before anything started" if moment == "start" else "at the end, under the benchmark's own load"
        print(f"- {when}: {averages.strip()}")
    print("A load above about 2 before the start means other work skewed every build; measure again when it is quiet.")
    conditions = run / "conditions"
    if conditions.exists() and "low-power-mode" in conditions.read_text():
        print("Low Power Mode was on, which slows the CPU: compare the builds with each other, not with other runs.")
    print()

statements = run / "statements.tsv"
if statements.exists():
    print("SQL statements in one GET /api/me after the first:\n")
    print("| Build | Statements |\n| --- | --- |")
    for variant, count in csv.reader(open(statements), delimiter="\t"):
        print(f"| {variant} | {count} |")
    print()

reads = run / "cache-reads.tsv"
if reads.exists():
    counted = dict(csv.reader(open(reads), delimiter="\t"))
    hits, misses = int(counted["hit"]), int(counted["miss"])
    if hits + misses:
        print(f"Cache reads during the measured runs: {hits} hits, {misses} misses, "
              f"hit rate {hits / (hits + misses):.2%}\n")

names = {"me": "GET /api/me", "issues": "GET /api/issues (one page)"}
print("Backend time per request, median of the rounds (range), and requests per second:\n")
print("| Endpoint | Concurrency | Build | Backend ms | vs before | Requests/s | vs before |")
print("| --- | --- | --- | --- | --- | --- | --- |")
for endpoint in ("me", "issues"):
    for concurrency in (1, 20):
        base = groups[(endpoint, concurrency, "before")]
        for variant in ("before", "cached", "bound"):
            group = groups[(endpoint, concurrency, variant)]
            ms, rps = median(group, "server_mean_ms"), median(group, "rps")
            if variant == "before":
                vs_ms = vs_rps = "—"
            else:
                vs_ms = f"{(ms - median(base, 'server_mean_ms')) / median(base, 'server_mean_ms'):+.0%}"
                vs_rps = f"{(rps - median(base, 'rps')) / median(base, 'rps'):+.0%}"
            print(f"| {names[endpoint]} | {concurrency} | {variant} | {ms:.3f} ({spread(group, 'server_mean_ms')}) "
                  f"| {vs_ms} | {rps:.0f} | {vs_rps} |")

print("\nRound by round: each build against before in the same round, which drift on the machine")
print("affects alike. Median of the per-round differences, and the rounds in which the build was faster:\n")
print("| Endpoint | Concurrency | Build | Difference ms | Difference | Faster in |")
print("| --- | --- | --- | --- | --- | --- |")
for endpoint in ("me", "issues"):
    for concurrency in (1, 20):
        by_rep = defaultdict(dict)
        for row in rows:
            if row["endpoint"] == endpoint and int(row["concurrency"]) == concurrency:
                by_rep[row["rep"]][row["variant"]] = float(row["server_mean_ms"])
        for variant in ("cached", "bound"):
            diffs = [times[variant] - times["before"] for times in by_rep.values()]
            shares = [(times[variant] - times["before"]) / times["before"] for times in by_rep.values()]
            faster = sum(1 for d in diffs if d < 0)
            print(f"| {names[endpoint]} | {concurrency} | {variant} | {statistics.median(diffs):+.2f} "
                  f"| {statistics.median(shares):+.0%} | {faster} of {len(diffs)} |")
