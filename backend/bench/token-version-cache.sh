#!/usr/bin/env bash
# Measures what the token version cache saves on a request. Three builds run side by
# side against the local Postgres and Redis and take turns under load, so whatever
# else the machine is doing hits them alike:
#
#   before  the backend as it was before the cache (BEFORE_REF)
#   cached  the backend as it is now, with Redis on
#   bound   the backend as it is now without the token version check at all: what
#           a cache that always hit and cost nothing would give
#
# It first counts the SQL statements one GET /api/me runs on before and on cached.
# Times are the backend's own, from http.server.requests, so the load generator and
# the connection set-up are left out.
#
# Needs Docker running `docker compose -f docker-compose.yml -f docker-compose.dev.yml
# up -d postgres redis`, the demo workspace seeded once (DEMO_SEED_ENABLED=true), ab,
# jq, python3, and the repository's .env.
#
# Usage, from backend/:  bench/token-version-cache.sh [repetitions, default 5]
set -euo pipefail

REPS=${1:-5}
BEFORE_REF=${BEFORE_REF:-748a3b1}
PROJECT_NAME=${PROJECT_NAME:-Web Platform}
HERE=$(cd "$(dirname "$0")" && pwd)
BACKEND=$(cd "$HERE/.." && pwd)
REPO=$(cd "$BACKEND/.." && pwd)
WORK=$BACKEND/target/token-version-cache-bench
OUT=$WORK/run-$(date +%Y%m%d-%H%M%S)
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
mkdir -p "$OUT"
: > "$OUT/pids"

set -a
# shellcheck disable=SC1091
. "$REPO/.env"
set +a
export REDIS_ENABLED=true

stop_all() {
    while read -r pid; do kill "$pid" 2>/dev/null || true; done < "$OUT/pids"
    wait 2>/dev/null || true
}
trap stop_all EXIT

# --- builds -------------------------------------------------------------------

build() { # source directory, jar to write
    (cd "$1" && ./mvnw -q -DskipTests package)
    cp "$1"/target/backend-*.jar "$2"
}

echo "building cached, before ($BEFORE_REF) and bound"
rm -rf "$WORK/src-before" "$WORK/src-bound"
mkdir -p "$WORK/src-before"
git -C "$REPO" archive "$BEFORE_REF" backend | tar -x -C "$WORK/src-before" --strip-components=1
rsync -a --exclude target "$BACKEND/" "$WORK/src-bound/"
JWT_CONFIG=$WORK/src-bound/src/main/java/com/vokyo/backend/security/JwtConfiguration.java
perl -0pi -e 's/JwtService\.API_AUDIENCE,\s*new TokenVersionValidator\(tokenVersionCache::currentVersion\)\s*\)/JwtService.API_AUDIENCE)/' "$JWT_CONFIG"
if grep -q "new TokenVersionValidator" "$JWT_CONFIG"; then
    echo "could not take the token version check out of the bound build" >&2
    exit 1
fi
build "$BACKEND" "$WORK/cached.jar"
build "$WORK/src-before" "$WORK/before.jar"
build "$WORK/src-bound" "$WORK/bound.jar"

# --- servers ------------------------------------------------------------------

start() { # name, port, extra arguments...
    local name=$1 port=$2
    shift 2
    SERVER_PORT=$port "$JAVA" -jar "$WORK/${name%-sql}.jar" "$@" > "$OUT/server-$name.log" 2>&1 &
    echo $! >> "$OUT/pids"
}

wait_up() { # port
    for _ in $(seq 1 120); do
        curl -sf "http://localhost:$1/actuator/health" > /dev/null 2>&1 && return 0
        sleep 1
    done
    echo "the backend on port $1 did not start" >&2
    exit 1
}

# The demo account comes from application.yaml's demo seed defaults.
login() { # port
    curl -sf -X POST "http://localhost:$1/api/auth/login" -H 'Content-Type: application/json' \
        -d '{"email":"demo@flowai.dev","password":"demo1234"}' | jq -r .accessToken
}

PROJECT=$(docker compose -f "$REPO/docker-compose.yml" exec -T postgres \
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select id from projects where name = '$PROJECT_NAME' limit 1")
[ -n "$PROJECT" ] || { echo "no project named $PROJECT_NAME; seed the demo workspace first" >&2; exit 1; }

# --- statements per request ---------------------------------------------------

echo "counting SQL statements per request"
start before-sql 18093 --logging.level.org.hibernate.SQL=DEBUG
start cached-sql 18094 --logging.level.org.hibernate.SQL=DEBUG
wait_up 18093
wait_up 18094
for pair in before-sql:18093 cached-sql:18094; do
    name=${pair%%:*}
    port=${pair##*:}
    token=$(login "$port")
    curl -sf -H "Authorization: Bearer $token" "http://localhost:$port/api/me" > /dev/null  # fills the cache
    sleep 1
    lines=$(wc -l < "$OUT/server-$name.log")
    curl -sf -H "Authorization: Bearer $token" "http://localhost:$port/api/me" > /dev/null
    sleep 1
    tail -n +$((lines + 1)) "$OUT/server-$name.log" | grep 'org.hibernate.SQL' | sed -E 's/.*org\.hibernate\.SQL *: *//' > "$OUT/sql-$name.txt" || true
    echo "${name%-sql}	$(wc -l < "$OUT/sql-$name.txt" | tr -d ' ')" >> "$OUT/statements.tsv"
done
stop_all
: > "$OUT/pids"

# --- load ---------------------------------------------------------------------

load_average() { sysctl -n vm.loadavg 2>/dev/null | tr -d '{}' || cut -d' ' -f1-3 /proc/loadavg; }
cores=$(sysctl -n hw.ncpu 2>/dev/null || nproc)

VARIANTS="before cached bound"
port_of() { case $1 in before) echo 18090 ;; cached) echo 18091 ;; bound) echo 18092 ;; esac; }
url_of() { # variant, endpoint
    case $2 in
        me) echo "http://localhost:$(port_of "$1")/api/me" ;;
        issues) echo "http://localhost:$(port_of "$1")/api/issues?projectId=$PROJECT" ;;
    esac
}
uri_of() { case $1 in me) echo "/api/me" ;; issues) echo "/api/issues" ;; esac; }
token_of() { cat "$OUT/token-$1"; }

server_stats() { # variant, endpoint -> "count total-seconds"
    curl -sf -H "Authorization: Bearer $(token_of "$1")" \
        "http://localhost:$(port_of "$1")/actuator/metrics/http.server.requests?tag=uri:$(uri_of "$2")" \
        | jq -r '[.measurements[] | select(.statistic=="COUNT" or .statistic=="TOTAL_TIME") | .value] | "\(.[0]) \(.[1])"'
}

cache_reads() { # result -> count on the cached build
    curl -sf -H "Authorization: Bearer $(token_of cached)" \
        "http://localhost:$(port_of cached)/actuator/metrics/flowai.auth.token_version_cache?tag=result:$1" \
        | jq -r '.measurements[0].value' 2>/dev/null || echo 0
}

echo "starting the three builds"
for variant in $VARIANTS; do start "$variant" "$(port_of "$variant")"; done
for variant in $VARIANTS; do
    wait_up "$(port_of "$variant")"
    login "$(port_of "$variant")" > "$OUT/token-$variant"
done

echo "warming up"
for variant in $VARIANTS; do
    for endpoint in me issues; do
        ab -q -n 10000 -c 10 -H "Authorization: Bearer $(token_of "$variant")" "$(url_of "$variant" "$endpoint")" > /dev/null
        ab -q -n 2000 -c 1 -H "Authorization: Bearer $(token_of "$variant")" "$(url_of "$variant" "$endpoint")" > /dev/null
    done
done

RESULTS=$OUT/results.tsv
printf 'variant\tendpoint\tconcurrency\trep\trps\tp50_ms\tp99_ms\tserver_mean_ms\tfailed\n' > "$RESULTS"
# Other work on the machine shows up as noise in every build alike; the load is
# recorded so a reader can tell a quiet run from a busy one.
echo "start	$(load_average)	$cores" > "$OUT/load.tsv"
hits_before=$(cache_reads hit)
misses_before=$(cache_reads miss)
for rep in $(seq 1 "$REPS"); do
    # A fresh token each round, as a run outlasts the 15-minute access tokens.
    for variant in $VARIANTS; do login "$(port_of "$variant")" > "$OUT/token-$variant"; done
    # Each round starts with a different build, so none always goes first.
    case $((rep % 3)) in
        1) order="before cached bound" ;;
        2) order="cached bound before" ;;
        0) order="bound before cached" ;;
    esac
    for endpoint in me issues; do
        for concurrency in 1 20; do
            for variant in $order; do
                file=$OUT/ab-$variant-$endpoint-$concurrency-$rep
                read -r count0 total0 < <(server_stats "$variant" "$endpoint")
                ab -n 5000 -c "$concurrency" -H "Authorization: Bearer $(token_of "$variant")" \
                    -e "$file.csv" "$(url_of "$variant" "$endpoint")" > "$file.txt" 2>&1
                read -r count1 total1 < <(server_stats "$variant" "$endpoint")
                rps=$(awk '/Requests per second/ {print $4}' "$file.txt")
                failed=$(awk '/Failed requests/ {print $3}' "$file.txt")
                non2xx=$(awk '/Non-2xx responses/ {print $3}' "$file.txt")
                p50=$(awk -F, '$1==50 {print $2}' "$file.csv")
                p99=$(awk -F, '$1==99 {print $2}' "$file.csv")
                mean=$(python3 -I -c "print(f'{($total1 - $total0) / ($count1 - $count0) * 1000:.3f}')")
                printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$variant" "$endpoint" "$concurrency" "$rep" \
                    "$rps" "$p50" "$p99" "$mean" "$((failed + ${non2xx:-0}))" | tee -a "$RESULTS"
            done
        done
    done
done
echo "end	$(load_average)	$cores" >> "$OUT/load.tsv"
hits=$(python3 -I -c "print(int($(cache_reads hit) - $hits_before))")
misses=$(python3 -I -c "print(int($(cache_reads miss) - $misses_before))")
printf 'hit\t%s\nmiss\t%s\n' "$hits" "$misses" > "$OUT/cache-reads.tsv"

python3 -I "$HERE/summarize.py" "$OUT" | tee "$OUT/summary.md"
echo "raw results in $OUT"
