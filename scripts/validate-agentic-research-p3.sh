#!/usr/bin/env bash
set -euo pipefail

p3_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$p3_repo"
p3_container=${P3_POSTGRES_CONTAINER:-ragent-iron-ore-dev-postgres-1}
p3_database="research_p3_$(date -u +%Y%m%d%H%M%S)_${RANDOM}"
p3_created=false
cleanup_p3() {
  if [ "$p3_created" = true ]; then
    docker exec "$p3_container" sh -c 'exec dropdb -U "$POSTGRES_USER" "$1"' sh "$p3_database"
    echo 'Temporary P3 database removed.'
  fi
}
trap cleanup_p3 EXIT
trap 'exit 1' HUP INT TERM
docker exec "$p3_container" sh -c 'exec createdb -U "$POSTGRES_USER" "$1"' sh "$p3_database"
p3_created=true
docker exec -i "$p3_container" sh -c 'exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1' sh "$p3_database" < resources/database/schema_pg.sql
p3_port=$(docker inspect --format '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' "$p3_container")
p3_user=$(docker exec "$p3_container" sh -c 'printf "%s" "$POSTGRES_USER"')
p3_password=$(docker exec "$p3_container" sh -c 'printf "%s" "$POSTGRES_PASSWORD"')
p3_tests=${P3_TESTS:-ResearchRunPostgresIT,ResearchBudgetTest,ResearchNativeToolsTest,ResearchRunControllerTest}
RESEARCH_P3_TEST_URL="jdbc:postgresql://127.0.0.1:${p3_port}/${p3_database}" \
  RESEARCH_TEST_PG_USER="$p3_user" RESEARCH_TEST_PG_PASSWORD="$p3_password" \
  ./mvnw -o -pl bootstrap -am "-Dtest=$p3_tests" -Dsurefire.failIfNoSpecifiedTests=false test
unset p3_password
