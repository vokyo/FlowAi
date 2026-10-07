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
# Running it again is safe: it creates only what is missing and resets the password.
set -eu
: "${AGENT_DB_PASSWORD:?AGENT_DB_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v agent_password="$AGENT_DB_PASSWORD" <<'SQL'
SELECT 'CREATE ROLE flowai_agent'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'flowai_agent')\gexec

ALTER ROLE flowai_agent LOGIN PASSWORD :'agent_password';
CREATE SCHEMA IF NOT EXISTS agent_checkpoint AUTHORIZATION flowai_agent;
-- LangGraph creates and queries its tables without a schema name.
ALTER ROLE flowai_agent SET search_path = agent_checkpoint;
SQL
