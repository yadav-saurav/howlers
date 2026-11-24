#!/usr/bin/env sh
# Rough database cost per anonymous request on the Docker stack: Postgres transactions and rows read per call,
# measured from pg_stat_database before and after 50 sequential requests to each endpoint. Read-only.
# Statements outside a Spring transaction each count as one transaction, so "xacts/req" approximates queries per request.
# Usage (from the repo root): sh loadtest/count-db-work.sh
set -eu
DOCKER="${DOCKER:-docker}"
BASE="${BASE:-http://localhost:8088/api/v1}"
stat() {
  "$DOCKER" exec reddit-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select xact_commit + xact_rollback, tup_returned + tup_fetched from pg_stat_database where datname = current_database()"'
}
for ep in "posts/4" "posts/4/comments?sort=best&limit=20" "feed?tab=popular&limit=20" "feed?tab=latest&limit=20" \
          "communities/programming/posts?sort=hot" "communities/programming" "communities/trending?limit=5" \
          "communities?sort=popular&page=0&size=20"; do
  before=$(stat)
  i=0
  while [ $i -lt 50 ]; do curl -s -o /dev/null "$BASE/$ep"; i=$((i + 1)); done
  after=$(stat)
  echo "$before $after" | awk -v ep="$ep" -F'[| ]' '{ printf "%-45s %6.1f xacts/req %9.0f rows/req\n", ep, ($3 - $1 - 1) / 50, ($4 - $2) / 50 }'
done
