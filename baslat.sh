#!/usr/bin/env bash
# Office Manipulator — internetsiz ortamda tek komutla başlatma.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# ── Yardımcı fonksiyonlar ──────────────────────────────────────────────────────
err()  { echo "HATA: $*" >&2; }
hint() { echo "  → $*" >&2; }

port_in_use() {
  ss -tlnH "sport = :$1" 2>/dev/null | grep -q . || \
  lsof -ti :"$1" >/dev/null 2>&1
}

# ── 1. Docker kurulu mu? ───────────────────────────────────────────────────────
command -v docker >/dev/null 2>&1 || {
  err 'Docker kurulu değil.'
  hint 'https://docs.docker.com/engine/install/ adresinden yükleyin.'
  exit 1
}
docker compose version >/dev/null 2>&1 || {
  err 'Docker Compose v2 bulunamadı.'
  hint 'Docker Desktop veya "docker-compose-plugin" paketini yükleyin.'
  exit 1
}

# ── 2. Docker daemon çalışıyor mu? ────────────────────────────────────────────
if ! docker info >/dev/null 2>&1; then
  echo 'Docker çalışmıyor, başlatılıyor...'
  # Docker Desktop (Linux/Mac)
  systemctl --user start docker-desktop 2>/dev/null || true
  # Docker Engine (systemd)
  sudo systemctl start docker 2>/dev/null || true
  for i in $(seq 1 20); do
    docker info >/dev/null 2>&1 && break
    sleep 2
  done
  docker info >/dev/null 2>&1 || {
    err 'Docker başlatılamadı.'
    hint 'Docker Desktop uygulamasını elle açın ve tekrar deneyin.'
    exit 1
  }
  echo 'Docker hazır.'
fi

# ── 3. .env kontrolü ──────────────────────────────────────────────────────────
if [[ ! -f .env ]]; then
  err '.env dosyası bulunamadı.'
  hint "Aşağıdaki komutla oluşturun:"
  hint "  cp .env.example .env   # yoksa elle oluşturun"
  hint "  DB_PASSWORD ve OFFICE_JWT_SECRET değerlerini doldurun."
  exit 1
fi

# Boş veya varsayılan değer kalmış mı?
for key in DB_PASSWORD OFFICE_JWT_SECRET; do
  val=$(grep -E "^${key}=" .env | cut -d= -f2- | tr -d '[:space:]')
  if [[ -z "$val" || "$val" == "changeme" ]]; then
    err ".env içinde $key boş ya da 'changeme'."
    hint "Rastgele üretmek için:  openssl rand -hex 32"
    exit 1
  fi
done

# ── 4. Gerekli imaj dosyaları var mı? ─────────────────────────────────────────
missing=()
for f in app.tar postgres.tar onlyoffice.tar; do
  [[ -f "$f" ]] || missing+=("$f")
done
if [[ ${#missing[@]} -gt 0 ]]; then
  err "Şu imaj dosyaları eksik: ${missing[*]}"
  hint 'Teslim paketinin tamamını aynı klasörde bulundurun.'
  exit 1
fi

# ── 5. Port çakışması ön kontrolü ─────────────────────────────────────────────
export COMPOSE_PROJECT_NAME=office-manipulator-teslim

blocked=()
for port in 8081 8082 5432; do
  if port_in_use "$port"; then
    # Kendi containerımız mı tutuyor? O zaman sorun yok.
    if ! docker ps --format '{{.Ports}}' | grep -q "127.0.0.1:${port}->"; then
      blocked+=("$port")
    fi
  fi
done
if [[ ${#blocked[@]} -gt 0 ]]; then
  err "Şu portlar başka uygulamalar tarafından kullanılıyor: ${blocked[*]}"
  hint "Hangi process kullandığını görmek için:"
  for p in "${blocked[@]}"; do
    hint "  sudo lsof -i :$p -n -P"
  done
  hint 'İlgili uygulamayı durdurup tekrar deneyin.'
  exit 1
fi

# ── 6. Eski volume / şifre uyuşmazlığı uyarısı ───────────────────────────────
if docker volume ls --format '{{.Name}}' | grep -q 'office-manipulator-teslim_pgdata'; then
  stored_pass=$(grep -E '^DB_PASSWORD=' .env | cut -d= -f2- | tr -d '[:space:]')
  # Postgres'e bağlanmayı dene; hata verirse şifre değişmiş demektir.
  if ! docker run --rm --network office-manipulator-teslim_default \
       -e PGPASSWORD="$stored_pass" postgres:16-alpine \
       psql -h postgres -U app -d officemanipulator -c '\q' >/dev/null 2>&1; then
    err 'Mevcut veritabanı şifresi .env ile uyuşmuyor.'
    hint 'Eski veriyi silip temiz başlatmak için:'
    hint '  docker compose -p office-manipulator-teslim down -v'
    hint 'Ardından baslat.sh yi tekrar çalıştırın.'
    exit 1
  fi
fi

# ── 7. İmajları yükle ─────────────────────────────────────────────────────────
echo '[1/3] İmajlar yükleniyor (biraz sürebilir)...'
docker load -i app.tar
docker load -i postgres.tar
docker load -i onlyoffice.tar

# ── 8. Yapılandırma doğrula ───────────────────────────────────────────────────
echo '[2/3] Yapılandırma kontrol ediliyor...'
docker compose --env-file .env -f docker-compose.yml config --quiet

# ── 9. Servisleri başlat ──────────────────────────────────────────────────────
echo '[3/3] Servisler başlatılıyor (OnlyOffice ~2-3 dk sürebilir)...'
if ! docker compose --env-file .env -f docker-compose.yml up -d --no-build --pull never --wait --wait-timeout 600; then
  echo >&2
  logs=$(docker compose --env-file .env -f docker-compose.yml logs 2>&1)
  if echo "$logs" | grep -q 'address already in use'; then
    err '8081 veya 8082 portu başlatma sırasında kaplandı.'
    hint 'sudo lsof -i :8081 -i :8082  ile bulun ve durdurun.'
  elif echo "$logs" | grep -q 'password authentication failed'; then
    err 'Veritabanı şifresi uyuşmuyor (eski volume).'
    hint 'docker compose -p office-manipulator-teslim down -v'
    hint 'Ardından baslat.sh yi tekrar çalıştırın.'
  else
    err 'Servisler sağlıklı duruma geçemedi.'
    hint 'docker compose -p office-manipulator-teslim logs --tail=60'
  fi
  exit 1
fi

# ── 10. Özet ─────────────────────────────────────────────────────────────────
docker compose --env-file .env -f docker-compose.yml ps
echo
echo 'Uygulama hazır: http://localhost:8081'
echo 'Durdurmak için: bash durdur.sh'
