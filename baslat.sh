#!/usr/bin/env bash
# Office Manipulator — internetsiz ortamda tek komutla başlatma.
# Bu dosyanın yanındaki app.tar/postgres.tar/onlyoffice.tar imajlarını yükler,
# .env içindeki zorunlu alanların doldurulduğunu doğrular ve servisleri ayağa kaldırır.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

command -v docker >/dev/null 2>&1 || { echo 'Docker Engine kurulu olmalı.' >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo 'Docker Compose v2 kurulu olmalı.' >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo 'Docker servisi çalışmıyor görünüyor. Docker'"'"'ı başlatıp tekrar deneyin.' >&2; exit 1; }

if [[ ! -f .env ]]; then
  echo 'Bu klasörde .env dosyası bulunamadı.' >&2
  exit 1
fi

if grep -qE '^(DB_PASSWORD|OFFICE_JWT_SECRET)=(changeme)?$' .env; then
  echo '.env içinde DB_PASSWORD ve/veya OFFICE_JWT_SECRET boş ya da hâlâ "changeme". Önce bunları doldurun:' >&2
  echo '  openssl rand -hex 32   # OFFICE_JWT_SECRET için' >&2
  exit 1
fi

for f in app.tar postgres.tar onlyoffice.tar; do
  [[ -f "$f" ]] || { echo "Gerekli imaj dosyası eksik: $f" >&2; exit 1; }
done

echo '[1/3] İmajlar yükleniyor (biraz sürebilir)...'
docker load -i app.tar
docker load -i postgres.tar
docker load -i onlyoffice.tar

# Dizin adından türeyen proje adıyla çakışmayı önlemek için sabit bir proje adı kullanıyoruz —
# aynı Docker ortamında başka bir "office-manipulator" projesiyle port çakışması riskini azaltır.
export COMPOSE_PROJECT_NAME=office-manipulator-teslim

echo '[2/3] Yapılandırma kontrol ediliyor...'
docker compose --env-file .env -f docker-compose.yml config --quiet

start_once() {
  # --force-recreate: önceki başarısız bir denemeden "Created" durumda kalmış bir konteyner varsa
  # (örn. port çakışması yüzünden hiç başlayamamış), Compose onu olduğu gibi başlatmaya çalışmak
  # yerine (ki bu durumda port eşlemesi eksik kalabilir) sıfırdan, doğru yapılandırmayla yeniden
  # oluşturur.
  docker compose --env-file .env -f docker-compose.yml up -d --no-build --pull never --force-recreate --wait --wait-timeout 600 || return 1
  # Sağlık kontrolünden geçmiş olsa bile port eşlemesinin gerçekten var olduğunu doğrula —
  # nadir bir Docker ağ yarış durumunda konteyner "healthy" olup host portunu kaybedebiliyor.
  docker port office-manipulator-teslim-onlyoffice-1 2>/dev/null | grep -q . || return 1
  docker port office-manipulator-teslim-app-1 2>/dev/null | grep -q . || return 1
}

echo '[3/3] Servisler başlatılıyor...'
if ! start_once; then
  echo 'İlk deneme tam başlamadı, temizlenip tekrar deneniyor...' >&2
  docker compose --env-file .env -f docker-compose.yml down >/dev/null 2>&1 || true
  if ! start_once; then
    echo >&2
    echo 'Servisler sağlıklı/doğru port eşlemesiyle başlayamadı. Olası neden: 8081 veya 8082 portu' >&2
    echo "başka bir uygulama/konteyner tarafından tutuluyor, ya da Docker'ın iç port kaydı bozulmuş." >&2
    echo 'Kontrol için:  docker compose -p office-manipulator-teslim ps' >&2
    echo '               docker compose -p office-manipulator-teslim logs --tail=100' >&2
    echo 'Çözülmezse:    sudo systemctl restart docker' >&2
    exit 1
  fi
fi

docker compose --env-file .env -f docker-compose.yml ps
echo
echo 'Uygulama hazır: http://localhost:8081'
echo 'Durdurmak için: bash durdur.sh'
