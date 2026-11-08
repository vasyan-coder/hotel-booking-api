#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if [ -e .env ]; then
  echo '.env already exists; keeping current configuration.'
  exit 0
fi
umask 077
secret=$(openssl rand -hex 32)
cat > .env <<ENV
JWT_SECRET=$secret
SEED_DATA=true
ENV
printf '%s\n' 'Created .env with a random signing key.'
