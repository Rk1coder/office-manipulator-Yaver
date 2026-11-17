#!/usr/bin/env python3
"""Model benchmark through the running app: the same plan → confirm → generate → validate flow a user goes through.

Usage (app running, key of an OpenAI-compatible provider such as NanoGPT in the environment):
  BENCH_API_KEY=... python3 scripts/benchmark.py text  --models a/b,c/d [--app http://localhost:8090] [--cap 14]
  BENCH_API_KEY=... python3 scripts/benchmark.py image --models seedream-v4.5,nano-banana-2 [--cap 14]
The app must run with LLM_PROVIDER-independent OPENAI_COMPATIBLE_BASE_URL pointing at that provider; each request
picks its model with llm_provider/llm_api_key/llm_model, so the app's own settings are left alone.
Results: benchmark-results/<date>-<kind>.json plus a Markdown table on stdout. Scoring is automatic only.
"""
import argparse, base64, html, concurrent.futures, csv, datetime, io, json, os, pathlib, re, threading, time, urllib.request, uuid, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DATA = ROOT / 'scripts' / 'benchmark-data'
OUT = ROOT / 'benchmark-results'
KEY = os.environ.get('BENCH_API_KEY', '')
BASE = os.environ.get('BENCH_BASE_URL', 'https://nano-gpt.com/api/v1').rstrip('/')
JOB_TIMEOUT = 40 * 60

TASKS = {
    'docx': ('DOCX', 'Küçük bir yazılım şirketi için uzaktan çalışma politikası hazırla. Amaç, kapsam, çalışma saatleri, ekipman, '
                     'güvenlik kuralları ve izin süreci bölümleri olsun. Ekipman için kim ne alır gösteren bir tablo ekle. Word dosyası olsun.'),
    'pptx': ('PPTX', 'Yeni başlayan bir kafe zinciri için yatırımcı sunumu hazırla: problem, çözüm, pazar büyüklüğü (Türkiye kahve pazarı), '
                     'iş modeli, 3 yıllık gelir hedefi, ekip ve yatırım talebi. 8 slayt civarı, konuşmacı notlarıyla.'),
    'xlsx': ('XLSX', '2027 için aylık ev bütçesi tablosu yap: gelir kalemleri, sabit giderler (kira, faturalar, ulaşım), değişken giderler, '
                     'aylık toplam ve yıllık toplam formülleriyle. Tasarruf oranı sütunu olsun.'),
    'csv': ('CSV', 'Bir e-ticaret sitesi için 25 satırlık örnek ürün listesi: ürün kodu, ad, kategori, fiyat (TL), stok adedi, KDV oranı.'),
    'merge': ('DOCX', 'Ekteki dört belgeyi (uzaktan çalışma politikası, kafe yatırımcı sunumu, ürün listesi ve toplantı kararları) inceleyip '
                      'yönetim kurulu için tek bir durum raporu hazırla. Toplantı kararlarındaki her tarih, sorumlu ve bütçe tutarı eksiksiz yer alsın; '
                      'sunumdaki yatırım talebi ve 3 yıllık ciro hedefleri de olsun. Ürün listesinden kategori bazında toplam stok tablosu çıkar.'),
}
EDIT = "Belgenin ana başlığını 'Uzaktan Çalışma Yönergesi' olarak değiştir; başka hiçbir şeyi değiştirme."
DOCX_SECTIONS = ['amaç', 'kapsam', 'çalışma saat', 'ekipman', 'güvenlik', 'izin']
MERGE_FILES = ['politika-edit.docx', 'sunum.pptx', 'urunler.csv', 'toplanti-kararlari.csv']
IMAGE_PROMPTS = ['Modern bir kafede dizüstü bilgisayarla çalışan genç insanlar, sıcak ışık, sunum görseli, yazı yok',
                 'Kahve çekirdeklerinden yükselen büyüme grafiği, minimal kurumsal illüstrasyon, beyaz arka plan, yazı yok',
                 'Evden çalışma masası: monitör, kulaklık, bitki; düz renkli vektör illüstrasyon, yazı yok']


def merge_facts():
    """Every fact the merged report must keep: the meeting rows, the deck's ask and targets, the stock totals."""
    facts = []
    for row in csv.DictReader(open(DATA / 'toplanti-kararlari.csv', encoding='utf-8')):
        facts += [row['Sorumlu'].split(' - ')[-1], row['Bütçe (TL)']]
        facts += [('date', d) for d in (row['Tarih'], row['Son Tarih'])]
    stock = {}
    for row in csv.DictReader(open(DATA / 'urunler.csv', encoding='utf-8')):
        stock[row['Kategori']] = stock.get(row['Kategori'], 0) + int(row['Stok Adedi'])
    facts += [str(v) for v in stock.values()] + [str(sum(stock.values())), '6 milyon', 'Kadıköy', '14 adet']
    return [f for f in facts if f != '0'], sum(stock.values())


AYLAR = ['Ocak', 'Şubat', 'Mart', 'Nisan', 'Mayıs', 'Haziran', 'Temmuz', 'Ağustos', 'Eylül', 'Ekim', 'Kasım', 'Aralık']


def has_fact(text, fact):
    flat = lambda s: re.sub(r'[\s.]', '', s.replace('İ', 'i').lower())
    if isinstance(fact, tuple):  # a date may be written 2027-01-12, 12.01.2027 or 12 Ocak 2027
        y, m, d = fact[1].split('-')
        forms = [fact[1], f'{d}.{m}.{y}', f'{int(d)}.{m}.{y}', f'{int(d)} {AYLAR[int(m) - 1]} {y}', f'{d} {AYLAR[int(m) - 1]} {y}']
        return any(f in text for f in forms)
    return flat(fact) in flat(text)


def http(method, url, data=None, headers=None, timeout=120):
    headers = dict(headers or {})
    if isinstance(data, dict):
        data = json.dumps(data).encode(); headers['Content-Type'] = 'application/json'
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=timeout) as r:
        return r.read()


def multipart(fields, files):
    boundary = uuid.uuid4().hex
    body = io.BytesIO()
    for k, v in fields.items():
        body.write(f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode())
    for name in files:
        body.write(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                   f'Content-Type: application/octet-stream\r\n\r\n'.encode())
        body.write((DATA / name).read_bytes()); body.write(b'\r\n')
    body.write(f'--{boundary}--\r\n'.encode())
    return body.getvalue(), {'Content-Type': f'multipart/form-data; boundary={boundary}'}


class App:
    def __init__(self, url): self.url = url.rstrip('/')
    def get(self, path): return json.loads(http('GET', self.url + path))
    def post(self, path, data=None, headers=None): return json.loads(http('POST', self.url + path, data, headers) or b'{}')

    def wait(self, job_id, until):
        start = time.time()
        while time.time() - start < JOB_TIMEOUT:
            job = self.get(f'/api/jobs/{job_id}')
            if job['status'] in until or job['status'] in ('DONE', 'FAILED', 'CANCELLED'):
                return job
            time.sleep(4)
        self.post(f'/api/jobs/{job_id}/cancel')
        return {**job, 'status': 'TIMEOUT'}


def text_of(path, kind):
    z = zipfile.ZipFile(path)
    if kind == 'docx':
        x = z.read('word/document.xml').decode()
        return html.unescape(' '.join(re.findall(r'<w:t[^>]*>([^<]*)</w:t>', x))), x
    raise ValueError(kind)


def paragraphs(path):
    x = zipfile.ZipFile(path).read('word/document.xml').decode()
    # Entities are unescaped: a re-saved file writes &quot; as " without any text change.
    return [html.unescape(''.join(re.findall(r'<w:t[^>]*>([^<]*)</w:t>', p))) for p in re.findall(r'<w:p[ >].*?</w:p>', x, re.S)]


def check(task, path):
    """Format-specific automatic checks: (passed, note)."""
    if task == 'docx':
        text, xml = text_of(path, 'docx')
        found = [s for s in DOCX_SECTIONS if s in text.replace('İ', 'i').lower()]
        ok = '<w:tbl>' in xml and len(found) == len(DOCX_SECTIONS)
        return ok, f'{len(found)}/{len(DOCX_SECTIONS)} bölüm, tablo {"var" if "<w:tbl>" in xml else "yok"}'
    if task == 'pptx':
        z = zipfile.ZipFile(path)
        slides = [n for n in z.namelist() if re.match(r'ppt/slides/slide\d+\.xml$', n)]
        notes = [n for n in z.namelist() if re.match(r'ppt/notesSlides/notesSlide\d+\.xml$', n)
                 and re.search(r'<a:t>[^<]{20,}', z.read(n).decode())]
        return 6 <= len(slides) <= 12 and len(notes) >= len(slides) - 1, f'{len(slides)} slayt, {len(notes)} not'
    if task == 'xlsx':
        z = zipfile.ZipFile(path)
        sheets = [n for n in z.namelist() if re.match(r'xl/worksheets/sheet\d+\.xml$', n)]
        formulas = sum(len(re.findall(r'<f>', z.read(n).decode())) for n in sheets)
        return formulas >= 12, f'{len(sheets)} sayfa, {formulas} formül'
    if task == 'csv':
        rows = list(csv.reader(io.StringIO(path.read_bytes().decode('utf-8-sig'))))
        return len(rows) - 1 == 25 and all(len(r) >= 6 for r in rows), f'{len(rows) - 1} satır, {len(rows[0]) if rows else 0} sütun'
    if task == 'merge':
        text, xml = text_of(path, 'docx')
        facts, total = merge_facts()
        kept = [f for f in facts if has_fact(text, f)]
        pct = round(100 * len(kept) / len(facts))
        return pct >= 90 and has_fact(text, str(total)), f'olgu %{pct}, toplam stok {"doğru" if has_fact(text, str(total)) else "YOK"}'
    raise ValueError(task)


class Spend:
    """Running cost across threads; tasks stop starting once the cap is reached."""
    def __init__(self, cap): self.cap, self.total, self.lock = cap, 0.0, threading.Lock()
    def add(self, usd):
        with self.lock: self.total += usd
    def left(self):
        with self.lock: return self.total < self.cap


def prices():
    models = json.loads(http('GET', BASE + '/models?detailed=true', headers={'Authorization': 'Bearer ' + KEY}))['data']
    return {m['id']: m.get('pricing', {}) for m in models}


def run_model(app, model, price, spend, outdir):
    llm = {'llm_provider': 'openai_compatible', 'llm_api_key': KEY, 'llm_model': model}
    res = {'model': model, 'tasks': {}}
    usd = lambda j: (j.get('tokenInput', 0) * price.get('prompt', 0) + j.get('tokenOutput', 0) * price.get('completion', 0)) / 1e6
    docx_job = None
    for task in ['csv', 'docx', 'edit', 'xlsx', 'pptx', 'merge']:
        if not spend.left():
            res['tasks'][task] = {'status': 'SKIPPED', 'note': 'bütçe sınırı'}; continue
        t0 = time.time()
        try:
            if task == 'edit':
                if not docx_job:
                    res['tasks'][task] = {'status': 'SKIPPED', 'note': 'docx yok'}; continue
                job_id = app.post(f'/api/jobs/{docx_job}/edit', {'instruction': EDIT, **llm})['jobId']
                job = app.wait(job_id, ())
            else:
                fmt, msg = TASKS[task]
                if task == 'merge':
                    body, headers = multipart({'message': msg, 'format': fmt, **llm}, MERGE_FILES)
                    job_id = app.post('/api/chat/with-file', body, headers)['jobId']
                else:
                    job_id = app.post('/api/chat', {'message': msg, 'format': fmt, **llm})['jobId']
                job = app.wait(job_id, ('PLAN_READY',))
                if job['status'] == 'PLAN_READY':
                    app.post(f'/api/jobs/{job_id}/plan/confirm', {})
                    job = app.wait(job_id, ())
        except Exception as e:  # an HTTP error from the app is a failed task, not a crashed benchmark
            res['tasks'][task] = {'status': 'ERROR', 'note': str(e)[:200], 'seconds': round(time.time() - t0)}; continue
        cost = usd(job); spend.add(cost)
        entry = {'status': job['status'], 'seconds': round(time.time() - t0), 'tokens_in': job.get('tokenInput', 0),
                 'tokens_out': job.get('tokenOutput', 0), 'usd': round(cost, 4), 'error': (job.get('error') or '')[:300]}
        if job['status'] == 'DONE' and job.get('downloadUrl'):
            path = outdir / f'{model.replace("/", "_")}-{task}.{job["format"].lower()}'
            path.write_bytes(http('GET', app.url + job['downloadUrl']))
            if task == 'edit':
                before = paragraphs(outdir / f'{model.replace("/", "_")}-docx.docx'); after = paragraphs(path)
                changed = sum(a != b for a, b in zip(before, after)) + abs(len(before) - len(after))
                entry['ok'] = job.get('editMode') == 'TEMPLATE' and 'Uzaktan Çalışma Yönergesi' in ' '.join(after) and changed <= 2
                entry['note'] = f'{job.get("editMode")}, {changed} paragraf değişti'
            else:
                entry['ok'], entry['note'] = check(task, path)
            if task == 'docx': docx_job = job_id
        else:
            entry['ok'] = False
        res['tasks'][task] = entry
        print(f'[{model}] {task}: {entry["status"]} ok={entry.get("ok")} {entry.get("note", "")} {entry["seconds"]}s ${entry["usd"]}', flush=True)
    return res


def text_bench(args):
    app, table = App(args.app), prices()
    spend, outdir = Spend(args.cap), pathlib.Path(args.keep or OUT / 'files'); outdir.mkdir(parents=True, exist_ok=True)
    models = args.models.split(',')
    with concurrent.futures.ThreadPoolExecutor(args.parallel) as pool:  # the app runs 4 jobs at once by default
        results = list(pool.map(lambda m: run_model(app, m, table.get(m, {}), spend, outdir), models))
    return results, spend.total


def image_bench(args):
    table = json.loads(http('GET', BASE + '/image-models', headers={'Authorization': 'Bearer ' + KEY}))['data']
    table = {m['id']: m.get('pricing', {}).get('per_image', {}) for m in table}
    imgdir = OUT / 'images'; imgdir.mkdir(parents=True, exist_ok=True)
    spend, results = 0.0, []
    for model in args.models.split(','):
        per = table.get(model, {}); price = min(per.values()) if per else 0
        res = {'model': model, 'usd_per_image': price, 'images': []}
        for i, prompt in enumerate(IMAGE_PROMPTS, 1):
            if spend >= args.cap: res['images'].append({'status': 'SKIPPED'}); continue
            t0 = time.time()
            try:  # the exact body GeminiImageService sends with IMAGE_API_STYLE=images
                body = json.loads(http('POST', BASE + '/images/generations', {'model': model, 'prompt': prompt, 'n': 1, 'response_format': 'b64_json'},
                                       {'Authorization': 'Bearer ' + KEY}, timeout=300))
                item = body['data'][0]
                raw = base64.b64decode(item['b64_json']) if item.get('b64_json') else http('GET', item['url'])
                spend += price
                thumb = imgdir / f'{model.replace("/", "_")}-{i}.jpg'
                try:
                    from PIL import Image
                    im = Image.open(io.BytesIO(raw)).convert('RGB'); im.thumbnail((384, 384)); im.save(thumb, quality=80)
                except ImportError:
                    thumb.write_bytes(raw)
                res['images'].append({'status': 'DONE', 'seconds': round(time.time() - t0), 'file': str(thumb.relative_to(ROOT))})
            except Exception as e:
                res['images'].append({'status': 'ERROR', 'seconds': round(time.time() - t0), 'note': str(e)[:200]})
            print(f'[{model}] image {i}: {res["images"][-1]["status"]} {res["images"][-1].get("seconds")}s', flush=True)
        results.append(res)
    return results, spend


def table_md(kind, results):
    if kind == 'image':
        rows = ['| Model | Başarı | Ort. süre | $/görsel |', '|---|---|---|---|']
        for r in results:
            ok = [i for i in r['images'] if i['status'] == 'DONE']
            avg = round(sum(i['seconds'] for i in ok) / len(ok)) if ok else '—'
            rows.append(f'| `{r["model"]}` | {len(ok)}/{len(r["images"])} | {avg} sn | ${r["usd_per_image"]} |')
        return '\n'.join(rows)
    names = ['csv', 'docx', 'edit', 'xlsx', 'pptx', 'merge']
    rows = ['| Model | Başarı | ' + ' | '.join(names) + ' | Süre | Maliyet |', '|---|---|' + '---|' * len(names) + '---|---|']
    for r in results:
        t = r['tasks']; ok = sum(1 for n in names if t.get(n, {}).get('ok'))
        cell = lambda n: '✅' if t.get(n, {}).get('ok') else ('⏭' if t.get(n, {}).get('status') == 'SKIPPED' else ('⚠️' if t.get(n, {}).get('status') == 'DONE' else '❌'))
        secs = sum(t[n].get('seconds', 0) for n in names if n in t); usd = sum(t[n].get('usd', 0) for n in names if n in t)
        rows.append(f'| `{r["model"]}` | {ok}/{len(names)} | ' + ' | '.join(cell(n) for n in names) + f' | {round(secs / 60)} dk | ${usd:.2f} |')
    return '\n'.join(rows)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('kind', choices=['text', 'image'])
    p.add_argument('--models', required=True)
    p.add_argument('--app', default='http://localhost:8090')
    p.add_argument('--cap', type=float, default=14.0, help='stop starting new tasks after this many USD')
    p.add_argument('--parallel', type=int, default=4)
    p.add_argument('--keep', help='folder for generated files (default benchmark-results/files)')
    args = p.parse_args()
    if not KEY: raise SystemExit('BENCH_API_KEY is not set')
    results, total = (text_bench if args.kind == 'text' else image_bench)(args)
    OUT.mkdir(exist_ok=True)
    out = OUT / f'{datetime.date.today()}-{args.kind}.json'
    previous = json.loads(out.read_text()) if out.exists() else {'results': []}
    done = {r['model'] for r in results}  # a re-run of some models replaces only their rows
    merged = [r for r in previous['results'] if r['model'] not in done] + results
    out.write_text(json.dumps({'date': str(datetime.date.today()), 'base': BASE, 'results': merged}, ensure_ascii=False, indent=1))
    print(f'\nToplam harcama: ${total:.2f} → {out.relative_to(ROOT)}\n')
    print(table_md(args.kind, merged))
