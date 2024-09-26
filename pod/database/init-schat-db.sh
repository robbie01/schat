#!/bin/bash
set -e

echo "INIT_SCRIPT: Creating schat database..."

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
	CREATE USER schat;
	CREATE DATABASE schat;
	GRANT ALL PRIVILEGES ON DATABASE schat TO schat;
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname schat <<-EOSQL
	GRANT ALL ON SCHEMA public TO schat;
EOSQL

psql -v ON_ERROR_STOP=1 --username schat --dbname schat <<-EOSQL
    CREATE TABLE messages(id bigint primary key, channel text not null, username character varying(32) not null, msg text not null);
EOSQL