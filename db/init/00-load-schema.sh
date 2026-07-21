#!/usr/bin/env bash
set -euo pipefail

# Load the project's canonical db/schema.sql into the database that the postgres
# image already created from POSTGRES_DB.
#
# schema.sql opens with DROP DATABASE / CREATE DATABASE statements intended for a
# manual `psql -f schema.sql` bootstrap. Here the database already exists and we
# are connected to it, so those two lines are harmless no-ops — ON_ERROR_STOP=0
# lets psql skip past them and still apply every table, index and seed row.
#
# Init scripts run once, on first start, only while the data directory is empty.
psql -v ON_ERROR_STOP=0 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     --file /schema/schema.sql
