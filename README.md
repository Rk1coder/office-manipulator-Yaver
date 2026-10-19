# Office Manipulator — Kurulum Kılavuzu

Office Manipulator, Word/PDF belgelerini yükleyip tarayıcı üzerinden düzenleyebileceğiniz, yerel olarak çalışan bir belge yönetim platformudur.

---

## İnternet gerekliliği

Uygulamanın temel işlevleri **tamamen çevrimdışı** çalışır. İnternet bağlantısı yalnızca AI özelliklerini etkinleştirmek istediğinizde gereklidir.

| Özellik                                                 | İnternet gerekli mi?                         |
| ------------------------------------------------------- | -------------------------------------------- |
| Dosya yükleme ve indirme                                | Hayır                                        |
| Belge önizleme                                          | Hayır                                        |
| Biçim dönüştürme (Word → PDF vb.)                       | Hayır                                        |
| ONLYOFFICE ile tarayıcı içi belge düzenleme             | Hayır                                        |
| AI özellikleri (sohbet, belge üretimi, yazım önerileri) | **Evet — yalnızca Claude API anahtarı için** |

> AI özellikleri isteğe bağlıdır. `ANTHROPIC_API_KEY` boş bırakıldığında kapalı kalır; diğer tüm özellikler etkilenmez. Dış bağlantı yalnızca Anthropic API'sine yapılır — başka hiçbir servise erişilmez.

---

## Gereksinimler

Hedef bilgisayarda yalnızca şunların kurulu olması yeterlidir:

- **Docker Engine** ≥ 24 veya **Docker Desktop** (Compose v2 dahil)
- Docker servisi çalışır durumda

Java, Maven, Node.js gibi geliştirme araçları **gerekmez**; tümü imajların içindedir.

---

## Paket içeriği

Teslim klasöründe şu dosyalar bulunmalıdır:

```
app.tar              # Uygulama Docker imajı
postgres.tar         # Veritabanı Docker imajı
onlyoffice.tar       # Belge editörü Docker imajı
docker-compose.yml   # Servis tanımları
baslat.sh            # Başlatma betiği
durdur.sh            # Durdurma betiği
README.md            # Bu dosya
```

---

## Kurulum

### 1. Yapılandırmayı hazırlayın

Bu klasörde `.env` dosyası yoksa oluşturun:

```bash
cp .env.example .env
```

Ardından `.env` dosyasını bir metin editörüyle açıp şu iki alanı doldurun:

```env
DB_PASSWORD=güçlü-bir-parola-belirleyin
OFFICE_JWT_SECRET=  # aşağıdaki komutun çıktısını buraya yapıştırın
```

`OFFICE_JWT_SECRET` için rastgele bir değer üretin:

```bash
openssl rand -hex 32
```

`ANTHROPIC_API_KEY` satırını **boş bırakın** — AI özellikleri kapalı kalır, diğer işlevler bundan etkilenmez.

> Size önceden doldurulmuş bir `.env` dosyası verildiyse bu adımı atlayın.  
> `.env` dosyasını güvenli tutun; kimseyle paylaşmayın ve repoya eklemeyin.

### 2. Başlatın

```bash
bash baslat.sh
```

Betik şu adımları otomatik olarak gerçekleştirir:

1. Docker'ın kurulu ve çalışır olduğunu doğrular
2. `.env` dosyasındaki zorunlu alanları kontrol eder
3. Port çakışması olup olmadığını kontrol eder
4. Docker imajlarını yükler
5. Servisleri başlatır ve sağlıklı olana kadar bekler

İlk başlatmada OnlyOffice servisi ~2–3 dakika sürebilir.

### 3. Tarayıcıdan açın

Uygulama hazır olduktan sonra tarayıcınızda açın:

```
http://localhost:8081
```

> `localhost:8082` OnlyOffice editörüdür; uygulama tarafından otomatik kullanılır, doğrudan açmanız gerekmez.  
> Her iki adres de yalnızca bu bilgisayardan erişilebilir.

---

## Durdurma ve yeniden başlatma

```bash
bash durdur.sh    # Servisleri durdurur; veritabanı ve dosyalar korunur
bash baslat.sh    # Kaldığı yerden yeniden başlatır
```

---

## Durum kontrolü

Servislerin çalışıp çalışmadığını kontrol edin:

```bash
docker compose -p office-manipulator-teslim ps
```

Uygulama ve ONLYOFFICE entegrasyonunu doğrulayın:

```bash
curl -s http://localhost:8081/api/office/capabilities
```

JSON yanıtı dönüyorsa her şey çalışıyor demektir.

Günlükleri inceleyin:

```bash
docker compose -p office-manipulator-teslim logs --tail=100
docker compose -p office-manipulator-teslim logs -f app
```

---

## Ortam değişkenleri (`.env`)

| Değişken | Açıklama | Zorunlu mu? |
|---|---|---|
| `DB_PASSWORD` | PostgreSQL bağlantı parolası | **Evet** |
| `OFFICE_JWT_SECRET` | ONLYOFFICE ↔ uygulama imzalama anahtarı | **Evet** |
| `ANTHROPIC_API_KEY` | Claude API anahtarı (AI özellikleri için) | Hayır — boş bırakıldığında AI kapalı kalır |
| `ANTHROPIC_BASE_URL` | Özel Anthropic API uç noktası | Hayır — boş bırakın |
| `FILE_TTL_HOURS` | Dosyaların saklanma süresi (saat) | Hayır — varsayılan: `72` |
| `BUDGET_LIMIT_USD` | AI çağrıları için bütçe tavanı | Hayır — AI kapalıyken etkisizdir |
| `GEMINI_API_KEY` / `GEMINI_ENABLED` | Opsiyonel görsel AI | Hayır — boş/`false` bırakın |

---

## Sorun giderme

**Port zaten kullanımda hatası**

`baslat.sh` hangi portu kimin kullandığını otomatik tespit edip gösterir. Elle kontrol etmek isterseniz:

```bash
sudo lsof -i :8081 -i :8082 -n -P
```

Başka bir Docker container'ı tutuyorsa:

```bash
docker ps --format "table {{.Names}}\t{{.Ports}}"
```

**Veritabanı şifresi uyuşmuyor**

`.env` dosyasındaki `DB_PASSWORD` değeri değiştirildiyse eski veritabanı volumeı ile uyuşmaz. Temiz başlatmak için:

```bash
docker compose -p office-manipulator-teslim down -v
bash baslat.sh
```

> Bu komut veritabanındaki tüm verileri siler.

**Docker başlatılamıyor**

Docker Desktop kullanıyorsanız uygulamayı systray'den açın, ardından:

```bash
bash baslat.sh
```

Docker Engine kullanıyorsanız:

```bash
sudo systemctl start docker
bash baslat.sh
```

**İzin hatası (permission denied)**

Kullanıcınızı `docker` grubuna ekleyin ve oturumu yeniden açın:

```bash
sudo usermod -aG docker $USER
```

---

## AI özelliklerini etkinleştirme

AI özellikleri varsayılan olarak kapalıdır. Etkinleştirmek için:

1. [console.anthropic.com](https://console.anthropic.com) adresinden bir Claude API anahtarı edinin.
2. `.env` dosyasında `ANTHROPIC_API_KEY=sk-ant-...` satırını doldurun.
3. `bash durdur.sh && bash baslat.sh` ile yeniden başlatın.

Alternatif olarak anahtarı `.env` dosyasına yazmadan uygulamanın **Ayarlar** bölümünden de girebilirsiniz.

---

*Mimari, paketleme adımları ve test kaydı için: `docs/paketleme-ve-teslim.md`*
