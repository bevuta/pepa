#!/bin/bash

set -e

export PGPORT=$DB_PORT
export PGHOST=$DB_HOST
export PGUSER=$DB_USER
export PGDATABASE=$DB_NAME
export PGPASSWORD=$DB_PASS

echo "Checking database connectivity."

until psql -c '\l' 2>/dev/null >/dev/null; do
  >&2 echo "Postgres is unavailable - sleeping"
  sleep 1
done

>&2 echo "Postgres is up - we can now go on!"

VALUE=$(psql -qtA -c "SELECT COUNT(*) FROM pg_catalog.pg_tables WHERE tableowner = '${DB_USER}'")

PEPA_RUN="java -cp $(ls -1 pepa-*-standalone.jar | head -1)"

if [ $VALUE -eq 0 ]; then
    echo "Loading database schema"
    $PEPA_RUN pepa.tasks schema | psql
fi

$PEPA_RUN pepa.core
