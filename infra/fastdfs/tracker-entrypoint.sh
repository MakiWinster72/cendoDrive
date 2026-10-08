#!/bin/sh
set -eu

# This Compose stack is for local development; keep a nonzero disk safety margin.
reserve=${FDFS_RESERVED_STORAGE_SPACE:-5%}
if ! printf '%s\n' "$reserve" | grep -Eq '^([1-9]|[1-9][0-9])%$'; then
  echo 'FDFS_RESERVED_STORAGE_SPACE must be an integer percentage from 1% to 99%' >&2
  exit 1
fi
sed -i "s|^reserved_storage_space[[:space:]]*=.*$|reserved_storage_space = $reserve|" /etc/fdfs/tracker.conf
exec /usr/bin/entrypoint.sh "$@"
