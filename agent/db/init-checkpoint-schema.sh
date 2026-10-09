#!/bin/sh
# Creates the schema the planning agent keeps its LangGraph checkpoints in, and the
# role it connects as. The role owns that schema and is granted nothing else, so it
# cannot read or write the application's tables in public.
#
# Run by the postgres image on a fresh volume (mounted into
# /docker-entrypoint-initdb.d). On an existing volume, run it once by hand from the
# repository root, passing the password from .env:
#   docker compose exec -T -e AGENT_DB_PASSWORD="$(sed -n 's/^AGENT_DB_PASSWORD=//p' .env)" \
#       postgres sh -s < agent/db/init-checkpoint-schema.sh
# For a hosted database, such as Railway's, pass a URL of a role allowed to create
# roles as DATABASE_URL. Any image with psql can run it:
#   docker run --rm -i -e DATABASE_URL -e AGENT_DB_PASSWORD \
#       pgvector/pgvector:pg17 sh -s < agent/db/init-checkpoint-schema.sh
# Running it again is safe: it creates only what is missing and resets the password.
set -eu
: "${AGENT_DB_PASSWORD:?AGENT_DB_PASSWORD must be set}"

if [ -n "${DATABASE_URL:-}" ]; then
    set -- "$DATABASE_URL"
else
    set -- --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"
fi

psql -v ON_ERROR_STOP=1 "$@" -v agent_password="$AGENT_DB_PASSWORD" <<'SQL'
SELECT 'CREATE ROLE flowai_agent'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'flowai_agent')\gexec

ALTER ROLE flowai_agent LOGIN PASSWORD :'agent_password';
CREATE SCHEMA IF NOT EXISTS agent_checkpoint AUTHORIZATION flowai_agent;
-- LangGraph creates and queries its tables without a schema name.
ALTER ROLE flowai_agent SET search_path = agent_checkpoint;
SQL
