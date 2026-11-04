#!/usr/bin/env bash
set -euo pipefail

required=(
  MYSQL_DATABASE
  MYSQL_ROOT_PASSWORD
  DB_WEB_USERNAME
  DB_WEB_PASSWORD
  DB_PUBLISHER_USERNAME
  DB_PUBLISHER_PASSWORD
  DB_MIGRATION_USERNAME
  DB_MIGRATION_PASSWORD
)

for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Required environment variable is empty: $name" >&2
    exit 2
  fi
done

validate_identifier() {
  local name="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[A-Za-z0-9_]{1,32}$ ]]; then
    echo "$name must match [A-Za-z0-9_]{1,32}" >&2
    exit 2
  fi
}

validate_password() {
  local name="$1"
  local value="$2"
  if (( ${#value} < 16 || ${#value} > 128 )) || [[ ! "$value" =~ ^[A-Za-z0-9@%+=:,._-]+$ ]]; then
    echo "$name must be 16-128 characters from [A-Za-z0-9@%+=:,._-]" >&2
    exit 2
  fi
}

validate_identifier MYSQL_DATABASE "$MYSQL_DATABASE"
validate_identifier DB_WEB_USERNAME "$DB_WEB_USERNAME"
validate_identifier DB_PUBLISHER_USERNAME "$DB_PUBLISHER_USERNAME"
validate_identifier DB_MIGRATION_USERNAME "$DB_MIGRATION_USERNAME"
validate_password MYSQL_ROOT_PASSWORD "$MYSQL_ROOT_PASSWORD"
validate_password DB_WEB_PASSWORD "$DB_WEB_PASSWORD"
validate_password DB_PUBLISHER_PASSWORD "$DB_PUBLISHER_PASSWORD"
validate_password DB_MIGRATION_PASSWORD "$DB_MIGRATION_PASSWORD"

if [[ "$DB_WEB_USERNAME" == "$DB_PUBLISHER_USERNAME"
   || "$DB_WEB_USERNAME" == "$DB_MIGRATION_USERNAME"
   || "$DB_PUBLISHER_USERNAME" == "$DB_MIGRATION_USERNAME" ]]; then
  echo "Database usernames must be distinct" >&2
  exit 2
fi

MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql \
  --host="${DB_HOST:-mysql}" \
  --protocol=tcp \
  --user=root \
  --database="$MYSQL_DATABASE" <<SQL
CREATE USER IF NOT EXISTS '$DB_WEB_USERNAME'@'%' IDENTIFIED BY '$DB_WEB_PASSWORD';
ALTER USER '$DB_WEB_USERNAME'@'%' IDENTIFIED BY '$DB_WEB_PASSWORD';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$DB_WEB_USERNAME'@'%';
GRANT SELECT, SHOW VIEW ON \`$MYSQL_DATABASE\`.* TO '$DB_WEB_USERNAME'@'%';

CREATE USER IF NOT EXISTS '$DB_PUBLISHER_USERNAME'@'%' IDENTIFIED BY '$DB_PUBLISHER_PASSWORD';
ALTER USER '$DB_PUBLISHER_USERNAME'@'%' IDENTIFIED BY '$DB_PUBLISHER_PASSWORD';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$DB_PUBLISHER_USERNAME'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`$MYSQL_DATABASE\`.* TO '$DB_PUBLISHER_USERNAME'@'%';

CREATE USER IF NOT EXISTS '$DB_MIGRATION_USERNAME'@'%' IDENTIFIED BY '$DB_MIGRATION_PASSWORD';
ALTER USER '$DB_MIGRATION_USERNAME'@'%' IDENTIFIED BY '$DB_MIGRATION_PASSWORD';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$DB_MIGRATION_USERNAME'@'%';
GRANT ALL PRIVILEGES ON \`$MYSQL_DATABASE\`.* TO '$DB_MIGRATION_USERNAME'@'%';
FLUSH PRIVILEGES;
SQL

echo "Database accounts configured for $MYSQL_DATABASE"
