#!/bin/sh
# Existing MySQL/Redis/FastDFS containers only. Never migrate or drop the user's cendo DB.
set -eu
cd "$(dirname "$0")/.."
container=${CENDO_MYSQL_CONTAINER:-cendo-mysql-local}
database="cendo_acceptance_$(date +%Y%m%d%H%M%S)_$$"
export CENDO_ACCEPTANCE_DB_PASSWORD="$(docker exec "$container" printenv MYSQL_ROOT_PASSWORD)"
export CENDO_ACCEPTANCE_DB_USER=root
export CENDO_ACCEPTANCE_DB_URL="jdbc:mysql://127.0.0.1:3306/$database?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export CENDO_UPLOAD_ROOT="$(mktemp -d /tmp/cendo-acceptance-chunks.XXXXXX)"
export RUN_REAL_STACK_TESTS=true
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}
export PATH="$JAVA_HOME/bin:$PATH"
docker exec "$container" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "$1"' sh "CREATE DATABASE $database CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
printf 'Isolated real MySQL database: %s\n' "$database"
if mvn -q -Dtest=RealStackAcceptanceTest test; then
  if [ "${CENDO_ACCEPTANCE_KEEP_DB:-0}" != 1 ]; then
    docker exec "$container" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "$1"' sh "DROP DATABASE $database"
  else
    printf 'Kept empty acceptance database: %s\n' "$database"
  fi
  rmdir "$CENDO_UPLOAD_ROOT" 2>/dev/null || true
  printf 'Real-stack acceptance passed; generated users/files/session keys cleaned.\n'
else
  printf 'Acceptance failed; database %s and chunks %s retained for diagnosis and storage cleanup.\n' "$database" "$CENDO_UPLOAD_ROOT" >&2
  exit 1
fi
