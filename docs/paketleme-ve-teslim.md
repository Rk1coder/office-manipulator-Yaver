# Paketleme ve teslim — detaylı dokümantasyon

Bu doküman, teslim paketini **hazırlayan** kişi içindir (siz). Teslim alınan paketi sadece **çalıştıracak** kişi için kısa ve öz olan [README.md](../README.md) yeterlidir — o dosyayı değiştirmeyin, sadece bu dokümandaki bilgiyi kullanarak paketi doğru şekilde yeniden üretin/güncelleyin.

## 1. Paket içeriği

İnternetsiz hedefe taşınacak klasörde şu 8 dosya bulunur:

| Dosya | İçerik | Boyut (yaklaşık) |
|---|---|---|
| `app.tar` | Uygulamanın Docker imajı (`office-manipulator-app:latest`) | ~250 MB |
| `postgres.tar` | PostgreSQL 16 imajı | ~115 MB |
| `onlyoffice.tar` | ONLYOFFICE Document Server 10.0.0 imajı | ~920 MB |
| `docker-compose.yml` | Üç servisi tanımlayan Compose dosyası | — |
| `.env` | Doldurulmuş ortam değişkenleri (parolalar dahil) | — |
| `baslat.sh` | Tek komutla kurulum/başlatma betiği | — |
| `durdur.sh` | Durdurma betiği | — |
| `README.md` | Hedef makinedeki kişi için kurulum talimatı | — |
| `kaynak-kod.tar.gz` *(opsiyonel)* | Kaynak kodun yedek/arşiv kopyası — çalıştırmak için gerekmez | ~3 MB |

`node_modules/`, `target/`, `.git/`, `.idea/`, `.local/`, `pom.xml`, `src/` gibi hiçbir geliştirme/derleme dosyası pakette **olmamalı** — bunların hepsi `app.tar` içine zaten gömülüdür.

## 2. Paketi sıfırdan hazırlama

İnternet bağlantısı olan bu makinede, proje kök dizininde (`~/Desktop/office-manipulator`):

```bash
# 1) Yapılandırma (.env yoksa)
cp .env.example .env
openssl rand -hex 32            # çıktıyı OFFICE_JWT_SECRET=... olarak .env'e yazın
nano .env                       # DB_PASSWORD'ü de kendi parolanızla değiştirin, ANTHROPIC_API_KEY boş kalsın

# 2) İmajları derle/çek
docker compose config --quiet
docker compose pull postgres onlyoffice
docker compose build

# 3) İmajları dosyaya kaydet
docker save office-manipulator-app -o app.tar
docker save postgres:16-alpine -o postgres.tar
docker save onlyoffice/documentserver:10.0.0 -o onlyoffice.tar

# 4) Teslim klasörünü oluştur
mkdir -p ~/office-manipulator-teslim
cp app.tar postgres.tar onlyoffice.tar docker-compose.yml .env README.md baslat.sh durdur.sh ~/office-manipulator-teslim/

# 5) (opsiyonel) Kaynak kod arşivi
tar -czf ~/office-manipulator-teslim/kaynak-kod.tar.gz \
  --exclude='node_modules' --exclude='target' --exclude='.git' \
  --exclude='.idea' --exclude='.local' --exclude='*.tar' \
  --exclude='.env' --exclude='.office.local.env' .
```

**Kod her değiştiğinde** sadece 2. ve 3. adımdaki `app.tar` ile ilgili satırları tekrarlamanız yeterli (postgres/onlyoffice imajları değişmez):

```bash
docker compose build
docker save office-manipulator-app -o app.tar
cp app.tar ~/office-manipulator-teslim/app.tar
```

Kaynak kodu da güncellemek isterseniz 5. adımı tekrarlayın.

## 3. `baslat.sh` / `durdur.sh` nasıl çalışır

`baslat.sh`:
1. Docker'ın kurulu ve çalışır olduğunu kontrol eder.
2. `.env` içinde `DB_PASSWORD`/`OFFICE_JWT_SECRET` doldurulmuş mu diye bakar (boş veya `changeme` ise başlamadan durur).
3. Üç `.tar` dosyasının var olduğunu doğrular, `docker load` ile yükler.
4. **`COMPOSE_PROJECT_NAME=office-manipulator-teslim` ortam değişkenini sabitler.** Bu kritik: proje adını klasör adından türetmek yerine sabit bir isim kullanmak, aynı makinede başka bir Docker Compose projesiyle (ör. aynı dizin adını paylaşan farklı bir kopya) çakışmayı önler.
5. `docker compose ... up -d --no-build --pull never --wait --wait-timeout 600` ile servisleri başlatır ve **sağlıklı** duruma geçmelerini bekler (`--wait` bayrağı sayesinde betik, servisler gerçekten hazır olmadan "başladı" demez).
6. Başarısız olursa hangi komutlarla (`docker compose -p office-manipulator-teslim logs`) inceleneceğini yazdırır.

`durdur.sh` aynı sabit proje adıyla `docker compose down` çalıştırır — veritabanı/dosya volume'leri silinmez.

### Neden `docker-compose.yml`'de `image: office-manipulator-app:latest` satırı var?

Compose dosyasında `app` servisi `build: .` kullanıyor ama **açık bir `image:` adı yoksa**, Compose imaj adını `{proje-adı}-app` şeklinde proje adından türetir. `baslat.sh` sabit bir proje adı (`office-manipulator-teslim`) kullandığında, eğer imaj build sırasında farklı bir proje adıyla (ör. `office-manipulator-app`, kök dizin adından) etiketlenmişse, Compose yüklenen imajı **bulamaz** ve "No such image" hatası verir. Bu gerçek bir hatadır ve bu oturumda canlı testte yakalandı — çözümü, `app` servisine projeden bağımsız sabit bir `image: office-manipulator-app:latest` adı vermekti. **Bu satırı docker-compose.yml'den kaldırmayın.**

## 4. Doğrulama kaydı (son test)

Bu paket, 2026-10-19 tarihinde uçtan uca test edilip doğrulandı:

- `baslat.sh` mantığıyla aynı adımlar (imaj yükleme → yapılandırma kontrolü → `up --wait`) alternatif portlarla (test amaçlı) çalıştırıldı.
- `postgres`, `onlyoffice`, `app` servislerinin üçü de **healthy** durumuna geçti.
- `curl http://localhost:<port>/api/office/capabilities` → `HTTP 200` ve beklenen JSON (`{"formats":[...],"enabled":true}`) döndü.
- Uygulama logları hatasız, Spring Boot ~5-6 saniyede ayağa kalktı.
- Test sırasında bir regresyon bulunup düzeltildi: `ClaudeKeyVerifier` sınıfına `ANTHROPIC_BASE_URL` desteği eklenirken oluşan birden fazla constructor, Spring'in hangisini kullanacağını belirleyememesine ("No default constructor found") yol açıyordu. Çözüm: asıl constructor'a `@Autowired` eklendi (bkz. `src/main/java/com/officemanipulator/config/ClaudeKeyVerifier.java`). Bu paketteki `app.tar` düzeltmeyi içerir.

## 5. Bu geliştirme makinesine özgü, karşılaşılabilecek bir sorun

Bu makinede birden fazla Docker Compose projesi (başka projeler, eski denemeler) uzun süredir aynı anda çalıştığı için test sırasında şu sorunla karşılaşıldı ve çözüldü — **hedef (temiz) makinede muhtemelen hiç yaşanmaz**, ama bilgi için:

- Daha önce zorla (`kill -9` veya benzeri) sonlandırılmış konteynerlerin `docker-proxy` yardımcı süreçleri bazen öksüz kalıp bir portu (örn. 8082) tutmaya devam edebiliyor — `docker ps` bunu göstermiyor, sadece `ps aux | grep docker-proxy` ile görülebiliyor.
- Bu süreci `sudo kill <pid>` ile kapatmak OS seviyesinde portu boşaltıyor ama **Docker daemon'ın kendi iç port tahsis tablosu** güncellenmeyebiliyor — bu yüzden `docker compose up` hâlâ "port is already allocated" diyebiliyor, `docker ps`/`ss` hiçbir şey göstermese bile.
- Kesin çözüm: `sudo systemctl restart docker`. Bu, makinedeki tüm konteynerleri kısaca durdurur; `restart: unless-stopped` politikalı servisler otomatik geri döner.

Temiz bir hedef makinede bu üç adımdan hiçbiri gerekmemeli; `bash baslat.sh` tek seferde çalışmalı.

## 6. Ortam değişkenleri — tam referans

| Değişken | Açıklama | Varsayılan / öneri |
|---|---|---|
| `DB_PASSWORD` | PostgreSQL parolası | zorunlu, kendiniz belirleyin |
| `OFFICE_JWT_SECRET` | ONLYOFFICE ↔ uygulama imzalama anahtarı | zorunlu, `openssl rand -hex 32` |
| `ANTHROPIC_API_KEY` | Claude API anahtarı | boş → AI kapalı (air-gapped varsayılanı) |
| `ANTHROPIC_BASE_URL` | Anthropic Messages API uç noktası | boş → resmî `https://api.anthropic.com`; yalnızca kendi barındırdığınız/proxy bir uç nokta için doldurun |
| `CLAUDE_MODEL` | Kullanılacak Claude modeli | `claude-sonnet-4-6` |
| `CLAUDE_INPUT_PRICE_PER_MILLION` / `CLAUDE_OUTPUT_PRICE_PER_MILLION` | Milyon token başına özel fiyat | boş → modele göre bilinen varsayılan |
| `GEMINI_API_KEY` / `GEMINI_ENABLED` | Opsiyonel AI görsel üretimi | boş / `false` |
| `GEMINI_IMAGE_MODEL` | Gemini görsel modeli | `gemini-3.1-flash-image-preview` |
| `GEMINI_INPUT_PRICE_PER_MILLION` / `GEMINI_OUTPUT_PRICE_PER_MILLION` | Gemini fiyatlandırması | `0` (pozitif değer girilmeden Gemini etkinleşmez) |
| `BUDGET_LIMIT_USD` | Toplam AI harcama tavanı | `20.00` |
| `FILE_TTL_HOURS` | Üretilen/yüklenen dosyaların saklanma süresi | `72` |
| `OFFICE_PUBLIC_URL` | ONLYOFFICE'in tarayıcıdan erişilen adresi | `http://localhost:8082` |
| `OFFICE_APP_PUBLIC_URL` | Uygulamanın tarayıcıdan erişilen adresi | `http://localhost:8081` |

`STORAGE_BASE_PATH`, `OFFICE_ENABLED`, `OFFICE_INTERNAL_URL`, `OFFICE_APP_INTERNAL_URL`, `SERVER_ADDRESS` konteyner içi sabit değerlerdir, `docker-compose.yml`'de tanımlıdır.

## 7. API özeti

Tüm uç noktalar `app` servisi üzerinden, varsayılan `http://localhost:8081`:

| Metod ve yol | Açıklama | AI gerektirir mi? |
|---|---|---|
| `POST /api/chat` | Doğal dil talimatıyla belge üretim işi başlatır | Evet — anahtar yoksa 503 |
| `GET /api/jobs`, `GET /api/jobs/{id}`, `GET /api/jobs/{id}/events` | İş geçmişi/durumu/SSE akışı | Hayır |
| `POST /api/jobs/{id}/edit`, `POST /api/jobs/{id}/edit-fragment` | Mevcut belgeyi düzenleme | Evet |
| `POST /api/jobs/{id}/cancel` | İşi iptal etme | Hayır |
| `GET /api/jobs/{id}/plan`, `POST /api/jobs/{id}/plan/confirm` | Üretim planını görüntüleme/onaylama | Evet |
| `POST /api/jobs/{id}/convert` | Biçim dönüştürme | Hayır |
| `GET /api/budget` | Bütçe/kullanım durumu | Hayır |
| `GET /api/files/{jobId}/download`, `GET /api/files/{jobId}/preview` | İndirme/önizleme | Hayır |
| `POST /api/office/import` | Mevcut belgeyi ONLYOFFICE ile düzenlemek üzere içe aktarma | Hayır |
| `GET /api/office/capabilities` | ONLYOFFICE entegrasyonu açık mı + desteklenen biçimler | Hayır |
| `POST /api/office/jobs/{id}/session`, `GET /api/office/sessions/{id}`, `POST /api/office/sessions/{id}/save` | ONLYOFFICE oturum yönetimi | Hayır |
| `POST /api/office/sessions/{id}/agent` | Yaver: seçili metin için AI öneri (metin + biçimlendirme) | Evet — anahtar yoksa 503 |
| `GET /office-plugin/config.json` | ONLYOFFICE plugin yapılandırması | Hayır |

## 8. Mimari ve paket yapısı özeti

```
Tarayıcı ──> app (Spring Boot, :8081→:8080) ──> postgres (:5432, yalnız localhost)
                 │                         └──> onlyoffice (:8082→:80, yalnız localhost)
                 └──(opsiyonel, internet)──> Anthropic Claude API / Google Gemini API
```

- **Backend**: Java 21, Spring Boot 3.3, PostgreSQL + Flyway, Apache POI (DOCX/PPTX/DOC), PDFBox/OpenPDF (PDF), ODF Toolkit (ODT/ODS/ODP).
- **Belge üretimi**: Claude, belgeyi üreten bir Node.js betiği (pptxgenjs/docx/exceljs/pdf-lib) yazar; bu betik Bubblewrap ile sandboxlanmış bir Node.js sürecinde çalıştırılır (bkz. [node-sandbox.md](node-sandbox.md)).
- **ONLYOFFICE entegrasyonu**: ayrı bir Document Server konteyneri; JWT imzalı, süreli biletlerle yetkilendirme. "Yaver" köprüsü (`office-plugin/bridge.js`) ONLYOFFICE'in plugin sandbox'ı içinde çalışıp metin okuma/yazma ve biçimlendirme uygular.
- **Frontend**: derleme adımı gerektirmeyen saf HTML/CSS/JS, Spring Boot tarafından statik olarak sunulur.

| Paket (`src/main/java/com/officemanipulator`) | Sorumluluk |
|---|---|
| `api` | REST controller'lar |
| `claude` | Claude/Anthropic entegrasyonu — orkestrasyon, araçlar, sistem promptları |
| `config` | Spring yapılandırması, Claude istemcisi/anahtar doğrulama |
| `domain` | JPA varlıkları |
| `generator` | Her çıktı biçimi için üretici + sandbox çalıştırıcı |
| `office` | ONLYOFFICE entegrasyonu |
| `preview` | Belge/önizleme verisi üretimi |
| `service` | İş mantığı — plan/düzenleme akışı, bütçe, dosya temizliği |
| `storage` | Disk üzerinde dosya kalıcılığı |
| `validator` | Üretilen çıktının doğrulanması |

## 9. İlgili dokümanlar

- [air-gapped-deployment.md](air-gapped-deployment.md) — imaj hazırlama/taşıma akışının orijinal, adım adım hâli.
- [onlyoffice-setup.md](onlyoffice-setup.md) — ayrı bir geliştirme/deneme (trial) ortamı.
- [node-sandbox.md](node-sandbox.md) — belge üretim sandbox'ı (Bubblewrap).
