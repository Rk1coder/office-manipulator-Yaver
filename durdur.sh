#!/usr/bin/env bash
# Office Manipulator — baslat.sh ile açılan servisleri durdurur.
# Veritabanı ve dosya volume'leri korunur; bash baslat.sh ile kaldığı yerden devam eder.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

export COMPOSE_PROJECT_NAME=office-manipulator-teslim
docker compose --env-file .env -f docker-compose.yml down
