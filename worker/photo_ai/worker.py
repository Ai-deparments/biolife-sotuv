#!/usr/bin/env python3
"""
Polka rasmi tahlili ishchisi (BioLife Agent AI).

API navbatidan bir to'da vizitni oladi (rasmi bor, hali tekshirilmagan; har agentga teng ulush) va:
  1. RITM rasmini yuklab, ffmpeg bilan kichraytiradi (uzun tomoni PHOTO_AI_MAX_SIDE);
  2. Claude (Max obuna, `claude` CLI orqali — shlyuz faqat matn qabul qiladi) bir chaqiruvda to'dadagi hamma rasmni ko'radi:
     polka rasmimi, aniqmi, BioLife guruhi mahsulotining nechta yuzi bor, qaysi brendlar, raqobatchilar, qisqa izoh;
  3. natijani API'ga qaytaradi. API standart bilan solishtirib hukm chiqaradi (ok / low / none / unclear / not_shelf)
     va PhotoChecked voqeasini chiqaradi → kunlik hisobot («Polka rasmi standarti») va panelning «Rasmlar» sahifasi.

Kun davomida ~5 000 vizit rasm bilan keladi; obuna boshqa agentlar bilan umumiy, shuning uchun soatlik chegara bor va
navbat har agentdan teng tanlaydi (namuna). Claude limiti to'lsa to'da navbatga qaytadi (urinish hisoblanmaydi).
Panelga holat «AI rasm tahlili» manbasi bo'lib boradi (SourceSynced).

RITM rasmni ~18–24 soatdan keyin o'chiradi (2026-10-18: kechagi navbatdagi 3 074 vizit «404» bo'lib qoldi). Shuning uchun
yon oqim har PHOTO_PREFETCH_SEC da Kuzatuv bazasidagi yangi rasmlarni keshga (PHOTO_CACHE_DIR) yuklab qo'yadi: kunduz
ulgurilmagan vizitlar tunda keshdan tahlil qilinadi. Vizitdagi bitta rasm o'chgan bo'lsa qolganlari bilan ishlanadi.

Sozlamalar (muhit, ~/biolife-sales/.env):
  AI_WORKER_USER, AI_WORKER_PASS    API servis hisobi (ADMIN; suhbat ishchisi bilan bir xil)
  BRIDGE_API                        API manzili (standart http://127.0.0.1:8130)
  CLAUDE_CLI                        standart `claude` (serverda /home/ubuntu/.local/bin/claude)
  PHOTO_AI_MODEL                    standart sonnet
  PHOTO_AI_MAX_PER_HOUR             soatiga ko'pi bilan nechta vizit (60)
  PHOTO_AI_BATCH                    bir Claude chaqiruvidagi vizitlar (5)
  PHOTO_AI_PER_VISIT                vizitdan nechta rasm (2; yangisi birinchi)
  PHOTO_AI_MAX_SIDE                 rasm uzun tomoni, px (1280)
  PHOTO_AI_POLL_SEC                 navbat bo'sh bo'lsa kutish (60)
  KUZATUV_DB                        Kuzatuv bazasi (ko'prik bilan bir xil); bo'sh bo'lsa kesh o'chiq
  PHOTO_CACHE_DIR                   rasm keshi (standart ~/biolife-sales/data/photo-cache)
  PHOTO_CACHE_DAYS                  keshda necha kun saqlanadi (3; kuniga ~0,6 GB)
  PHOTO_PREFETCH_SEC                keshni yangilash oralig'i (300)

Sinov (API'siz): python3 worker.py --try rasm1.jpg rasm2.jpg ...
Kesh sinovi: python3 worker.py --prefetch-once
"""
from __future__ import annotations

import base64
import collections
import concurrent.futures
import datetime as dt
import hashlib
import json
import logging
import os
import re
import signal
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
from zoneinfo import ZoneInfo

try:
    import requests
except ImportError:          # --try rejimi requests'siz ishlaydi
    requests = None

log = logging.getLogger("photo")


def env(name: str, default: str = "") -> str:
    v = os.environ.get(name, "")
    return v if v.strip() else default


API = env("BRIDGE_API", "http://127.0.0.1:8130").rstrip("/")
USER = env("AI_WORKER_USER")
PASS = env("AI_WORKER_PASS")
CLI = env("CLAUDE_CLI", "claude")
MODEL = env("PHOTO_AI_MODEL", "sonnet")
MAX_PER_HOUR = int(env("PHOTO_AI_MAX_PER_HOUR", "60"))
BATCH = max(1, min(10, int(env("PHOTO_AI_BATCH", "5"))))
PER_VISIT = max(1, min(4, int(env("PHOTO_AI_PER_VISIT", "2"))))
MAX_SIDE = int(env("PHOTO_AI_MAX_SIDE", "1280"))
POLL = int(env("PHOTO_AI_POLL_SEC", "60"))
MAX_DOWNLOAD = 15 * 1024 * 1024
KUZATUV_DB = env("KUZATUV_DB")
CACHE_DIR = env("PHOTO_CACHE_DIR", os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "data", "photo-cache")))
CACHE_DAYS = max(1, int(env("PHOTO_CACHE_DAYS", "3")))
PREFETCH_EVERY = max(60, int(env("PHOTO_PREFETCH_SEC", "300")))
PREFETCH_HOURS = 16                    # RITM rasmni ~18 soatdan keyin o'chiradi: undan eskisini qidirish befoyda
PREFETCH_MAX = 4000                    # bir o'tishda ko'pi bilan nechta rasm yuklanadi
TASHKENT = ZoneInfo("Asia/Tashkent")
BRANDS = ["BIOLIFE", "FRUCTIS", "Moxito", "Time Tea", "Sitro"]
KINDS = {"shelf", "outside", "other"}


class Retry(Exception):
    """Try this batch again later. count=False: a wait (limit, login), not the photos' fault."""
    def __init__(self, msg: str, delay: int = 0, count: bool = True):
        super().__init__(msg)
        self.delay, self.count = delay, count


class Fatal(Exception):
    """This visit's photo cannot be analysed (e.g. the file is gone)."""


# ------------------------------------------------------------------ API

class Api:
    def __init__(self) -> None:
        self.s = requests.Session()
        self.token: str | None = None

    def login(self) -> None:
        r = self.s.post(f"{API}/api/v1/auth/login", json={"username": USER, "password": PASS}, timeout=20)
        if r.status_code != 200:
            raise RuntimeError(f"API login {r.status_code}: {r.text[:200]}")
        self.token = r.json()["accessToken"]

    def call(self, method: str, path: str, **kw) -> requests.Response:
        if not self.token:
            self.login()
        for attempt in (1, 2):
            r = self.s.request(method, f"{API}{path}", headers={"Authorization": f"Bearer {self.token}"}, timeout=60, **kw)
            if r.status_code == 401 and attempt == 1:
                self.login()
                continue
            return r
        return r

    def claim(self, limit: int) -> dict | None:
        r = self.call("POST", "/api/v1/internal/photos/claim", params={"limit": limit})
        if r.status_code == 204:
            return None
        r.raise_for_status()
        return r.json()

    def result(self, visit_id: str, body: dict) -> None:
        r = self.call("POST", f"/api/v1/internal/photos/{visit_id}/result", json=body)
        if r.status_code >= 300:
            raise RuntimeError(f"natija qabul qilinmadi {r.status_code}: {r.text[:300]}")

    def fail(self, visit_id: str, error: str, retryable: bool, delay: int = 0, count: bool = True) -> None:
        self.call("POST", f"/api/v1/internal/photos/{visit_id}/fail",
                  json={"error": error[:900], "retryable": retryable, "delaySec": delay, "countAttempt": count})

    def status(self, ok: bool, detail: str) -> None:
        now = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
        self.call("POST", "/api/v1/internal/events/SourceSynced",
                  json={"source": "photo-ai", "title": "AI rasm tahlili", "syncedAt": now, "ok": ok, "detail": detail[:450]})


# ------------------------------------------------------------------ 1. RASM

def shrink(raw: bytes) -> bytes:
    """Uzun tomoni MAX_SIDE dan oshmaydigan JPEG. Claude baribir kichraytiradi; bu token va trafikni tejaydi."""
    with tempfile.TemporaryDirectory() as d:
        src, dst = os.path.join(d, "in"), os.path.join(d, "out.jpg")
        with open(src, "wb") as f:
            f.write(raw)
        scale = f"scale='if(gt(iw,ih),min({MAX_SIDE},iw),-2)':'if(gt(iw,ih),-2,min({MAX_SIDE},ih))'"
        p = subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", src, "-vf", scale, "-frames:v", "1", "-q:v", "4", dst],
                           capture_output=True, timeout=60)
        if p.returncode != 0 or not os.path.exists(dst):
            raise Fatal(f"rasm o'qilmadi: {p.stderr.decode(errors='replace')[-200:]}")
        with open(dst, "rb") as f:
            return f.read()


def download(url: str) -> bytes:
    try:
        r = requests.get(url, timeout=30, stream=True)
    except requests.RequestException as e:
        raise Retry(f"rasm yuklanmadi: {e}", 600)
    if r.status_code in (403, 404, 410):
        raise Fatal(f"rasm manbada yo'q ({r.status_code})")
    if r.status_code != 200:
        raise Retry(f"rasm yuklanmadi: {r.status_code}", 600)
    buf = bytearray()
    for chunk in r.iter_content(65536):
        buf += chunk
        if len(buf) > MAX_DOWNLOAD:
            raise Fatal("rasm juda katta")
    return bytes(buf)


def cache_path(url: str) -> str:
    h = hashlib.sha1(url.encode()).hexdigest()
    return os.path.join(CACHE_DIR, h[:2], h + ".jpg")


def cache_put(path: str, data: bytes) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = f"{path}.{threading.get_ident()}.part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def fetch(url: str) -> bytes:
    """Avval keshdan; bo'lmasa RITM'dan (va keshga yoziladi). O'chgan rasm — Fatal."""
    path = cache_path(url)
    try:
        with open(path, "rb") as f:
            return f.read()
    except FileNotFoundError:
        pass
    if os.path.exists(path + ".gone"):
        raise Fatal("rasm manbada yo'q (404)")
    data = download(url)
    try:
        cache_put(path, data)
    except OSError as e:
        log.warning("keshga yozilmadi: %s", e)
    return data


def prefetch_one(url: str) -> str:
    path = cache_path(url)
    if os.path.exists(path) or os.path.exists(path + ".gone"):
        return "bor"
    try:
        cache_put(path, download(url))
        return "yuklandi"
    except Fatal as e:
        if "manbada yo'q" in str(e):
            cache_put(path + ".gone", b"")
            return "o'chgan"
        return "xato"
    except Retry:
        return "xato"


def prune_cache() -> int:
    limit, removed = time.time() - CACHE_DAYS * 86400, 0
    for root, _, files in os.walk(CACHE_DIR):
        for name in files:
            p = os.path.join(root, name)
            try:
                if os.path.getmtime(p) < limit or (name.endswith(".part") and os.path.getmtime(p) < time.time() - 3600):
                    os.remove(p)
                    removed += 1
            except OSError:
                pass
    return removed


def prefetch_once() -> collections.Counter:
    """Kuzatuv'ga oxirgi PREFETCH_HOURS soatda kelgan RITM rasmlarini keshga yuklaydi (yangisi birinchi)."""
    since = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=PREFETCH_HOURS)).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    kz = sqlite3.connect(f"file:{KUZATUV_DB}?mode=ro", uri=True, timeout=30)
    try:
        urls = [r[0] for r in kz.execute(
            """select remote_url from photo where source_id = 'ritm-photo' and remote_url is not null and remote_url <> ''
               and received_at >= ? order by received_at desc""", (since,))]
    finally:
        kz.close()
    todo = [u for u in dict.fromkeys(urls) if not os.path.exists(cache_path(u)) and not os.path.exists(cache_path(u) + ".gone")]
    stats: collections.Counter = collections.Counter(jami=len(urls), bor=len(urls) - len(todo))
    with concurrent.futures.ThreadPoolExecutor(4) as ex:
        stats.update(ex.map(prefetch_one, todo[:PREFETCH_MAX]))
    return stats


def prefetch_loop() -> None:
    last_prune = 0.0
    while True:
        try:
            t0 = time.time()
            s = prefetch_once()
            if s.get("yuklandi") or s.get("o'chgan") or s.get("xato"):
                log.info("kesh: %d yangi rasm, %d o'chgan, %d xato (%.0f s)", s["yuklandi"], s["o'chgan"], s["xato"], time.time() - t0)
            if time.time() - last_prune > 3600:
                n = prune_cache()
                last_prune = time.time()
                if n:
                    log.info("kesh: %d eski fayl o'chirildi", n)
        except Exception as e:
            log.warning("kesh yangilanmadi: %s", e)
        time.sleep(PREFETCH_EVERY)


def visit_images(urls: list[str]) -> list[bytes]:
    """Vizit rasmlari (yangisi birinchi): o'chgan rasm o'tkazib yuboriladi, PER_VISIT tagacha olinadi."""
    images: list[bytes] = []
    gone: Fatal | None = None
    retry: Retry | None = None
    for u in urls:
        if len(images) >= PER_VISIT:
            break
        try:
            images.append(shrink(fetch(u)))
        except Fatal as e:
            gone = e
        except Retry as e:
            retry = e
    if images:
        return images
    if retry:
        raise retry
    raise gone or Fatal("vizitda rasm havolasi yo'q")


# ------------------------------------------------------------------ 2. CLAUDE: polkani ko'rish

SYSTEM = """Sen BioLife kompaniyasi (IMIR-TRADE GROUP) savdo bo'limining polka rasmi tekshiruvchisisan.
BioLife guruhi brendlari (hammasi ichimlik): BIOLIFE — ichimlik suvi (gazli va gazsiz, 0,5 / 1 / 1,5 / 5 / 10 l); FRUCTIS — limonadlar (Buratino, Grusha, Tarxun, Cola va boshqa ta'mlar); Moxito; Time Tea (Tima Tea) — sovuq choy; Sitro.
Boshqa hamma brendlar (Coca-Cola, Fanta, Sprite, Pepsi, Meva, Hydrolife va hokazo) — raqobatchi.

Senga savdo agentlarining do'kon vizitlarida olingan rasmlar beriladi; har vizitning o'z belgisi bor (v1, v2, ...). Har vizit uchun:
1. kind: "shelf" — ichimlik polkasi, muzlatkich, vitrina yoki do'kon ichidagi mahsulot terilgan joy ko'rinadi; "outside" — do'konning tashqi ko'rinishi; "other" — boshqa (selfi, hujjat, chek, bo'sh yoki tushunarsiz kadr). Vizitda bir nechta rasm bo'lsa, eng yaxshi polka rasmini asos qil.
2. clear: polkadagi mahsulot yorliqlarini ishonchli tanib sanash mumkinmi. false — xira, juda qorong'i, juda uzoqdan yoki asosiy qismi kadrga sig'magan.
3. facings: BioLife guruhi mahsulotining ko'rinadigan "yuzlari" soni — oldingi qatorda yorlig'i ko'rinib turgan butilka va qadoqlar. Orqa qatorlarni sanama. Polda turgan o'ralgan blok (pachka) — 1 yuz. Yorlig'ini tanimagan mahsulotni BioLife deb sanama, taxmin qilma.
4. brands: BioLife brendlari bo'yicha yuzlar, masalan {"FRUCTIS":4,"Time Tea":3}; faqat BIOLIFE, FRUCTIS, Moxito, Time Tea, Sitro kalitlari.
5. competitors: polkada eng ko'p joy egallagan 1–3 ta raqobatchi brend.
6. note: rahbar uchun bitta qisqa gap (o'zbek tilida, lotin yozuvida, 160 belgigacha): nima ko'rindi va agent keyingi safar nimani yaxshilasin.

Javobni FAQAT bitta JSON obyekt ko'rinishida ber, oldidan ham, keyin ham hech narsa yozma:
{"visits":[{"id":"v1","kind":"shelf","clear":true,"facings":0,"brands":{},"competitors":[],"note":"..."}]}"""


def cli_error(text: str) -> Retry:
    t = text[:300]
    if re.search(r"authenticat|oauth|/login|log ?in|session expired|credential", t, re.I):
        return Retry(f"Claude obunasiga kirish muddati tugagan — serverda qayta login kerak ({t[:120]})", 1800, count=False)
    if re.search(r"usage limit|limit reached|hit your limit|rate.?limit|quota|too many requests|overloaded|resets", t, re.I):
        return Retry(f"Claude obuna limiti to'ldi: {t[:140]}", 1800, count=False)
    return Retry(f"Claude: {t}", 600)


def claude(content: list[dict]) -> tuple[str, str]:
    """`claude -p` stream-json: bitta foydalanuvchi xabari (matn + rasmlar) → yakuniy result matni."""
    argv = [CLI, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
            "--system-prompt", SYSTEM, "--tools", "", "--safe-mode", "--model", MODEL,
            "--max-budget-usd", "1", "--no-session-persistence"]
    msg = json.dumps({"type": "user", "message": {"role": "user", "content": content}}) + "\n"
    with tempfile.TemporaryDirectory() as cwd:
        try:
            p = subprocess.run(argv, input=msg.encode(), capture_output=True, timeout=300, cwd=cwd)
        except FileNotFoundError:
            raise Retry(f"Claude CLI topilmadi: {CLI}", 1800, count=False)
        except subprocess.TimeoutExpired:
            raise Retry("Claude 5 daqiqada javob bermadi", 600)
    result, model = None, MODEL
    for line in p.stdout.decode(errors="replace").splitlines():
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        if ev.get("type") == "system" and ev.get("model"):
            model = str(ev["model"])
        if ev.get("type") == "result":
            result = ev
    if result is None:
        raise cli_error(p.stderr.decode(errors="replace").strip() or f"CLI {p.returncode}, javob yo'q")
    if result.get("is_error") or result.get("subtype") != "success":
        raise cli_error(str(result.get("result") or result.get("errors") or result.get("subtype")))
    return str(result.get("result") or ""), model


def extract_json(text: str) -> dict:
    t = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip())
    a, b = t.find("{"), t.rfind("}")
    if a < 0 or b <= a:
        raise ValueError("javobda JSON yo'q")
    return json.loads(t[a:b + 1])


def normalize(d: dict) -> dict:
    kind = str(d.get("kind") or "other").lower()
    try:
        facings = max(0, min(500, int(round(float(d.get("facings") or 0)))))
    except (TypeError, ValueError):
        facings = 0
    brands = {}
    for k, v in (d.get("brands") or {}).items() if isinstance(d.get("brands"), dict) else []:
        name = next((b for b in BRANDS if b.lower().replace(" ", "") == str(k).lower().replace(" ", "")), None)
        try:
            n = int(round(float(v)))
        except (TypeError, ValueError):
            continue
        if name and n > 0:
            brands[name] = min(500, n)
    comp = [str(c).strip()[:40] for c in (d.get("competitors") or []) if isinstance(c, str) and c.strip()][:3]
    return {"kind": kind if kind in KINDS else "other", "clear": d.get("clear") is not False, "facings": facings,
            "brands": brands, "competitors": comp, "note": str(d.get("note") or "").strip()[:300]}


def analyse(visits: list[dict], expected: int, standard: str | None) -> tuple[dict[str, dict], str]:
    """visits: [{ref, outletName, images: [jpeg bytes]}] → {ref: normalized}."""
    head = f"Vizitlar soni: {len(visits)}. Polka standarti: kamida {expected} ta BioLife yuzi."
    if standard:
        head += f"\nStandart haqida rahbar izohi: {standard}"
    content: list[dict] = [{"type": "text", "text": head}]
    for v in visits:
        n = len(v["images"])
        content.append({"type": "text", "text": f"Vizit {v['ref']} — do'kon: {v.get('outletName') or 'nomi yo`q'}, rasmlar: {n} ta"})
        for img in v["images"]:
            content.append({"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": base64.b64encode(img).decode()}})
    text, model = claude(content)
    try:
        d = extract_json(text)
    except (ValueError, json.JSONDecodeError):
        log.warning("Claude JSON bermadi, qayta so'ralmoqda")
        content.append({"type": "text", "text": "Faqat JSON obyekt bilan javob ber, boshqa hech narsa yozma."})
        text, model = claude(content)
        try:
            d = extract_json(text)
        except (ValueError, json.JSONDecodeError) as e:
            raise Retry(f"Claude javobi JSON emas: {e}", 600)
    out = {}
    for item in d.get("visits") or []:
        if isinstance(item, dict) and str(item.get("id")) in {v["ref"] for v in visits}:
            out[str(item["id"])] = normalize(item)
    return out, model


# ------------------------------------------------------------------ 3. BIR TO'DA

def process(api: Api, batch: dict) -> tuple[int, int]:
    """Bir to'da vizit: rasmlarni yuklash → bitta Claude chaqiruvi → har vizit natijasi. (tahlil qilingan, xato) qaytaradi."""
    expected = int(batch.get("expected") or 1)
    ready, failed = [], 0
    for i, item in enumerate(batch.get("items") or []):
        vid = item["visitId"]
        try:
            images = visit_images([u for u in (item.get("photoUrls") or []) if u])
            ready.append({"ref": f"v{i + 1}", "visitId": vid, "outletName": item.get("outletName"), "images": images})
        except Retry as e:
            api.fail(vid, str(e), True, e.delay, e.count)
            failed += 1
        except Fatal as e:
            api.fail(vid, str(e), False)
            failed += 1
    if not ready:
        return 0, failed
    try:
        results, model = analyse(ready, expected, batch.get("standardText"))
    except Retry as e:
        for v in ready:
            api.fail(v["visitId"], str(e), True, e.delay, e.count)
        raise
    done = 0
    for v in ready:
        r = results.get(v["ref"])
        if r is None:
            api.fail(v["visitId"], "Claude bu vizitga javob bermadi", True, 300, True)
            failed += 1
            continue
        api.result(v["visitId"], {**r, "photosSeen": len(v["images"]), "model": model})
        done += 1
    return done, failed


def hm(ts: float) -> str:
    return dt.datetime.fromtimestamp(ts, TASHKENT).strftime("%H:%M")


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    if not USER or not PASS:
        log.error("AI_WORKER_USER / AI_WORKER_PASS sozlanmagan")
        return 2
    log.info("rasm ishchisi: Claude CLI %s (%s), to'da %d, vizitdan %d rasm, soatiga %d vizit", CLI, MODEL, BATCH, PER_VISIT, MAX_PER_HOUR)
    if KUZATUV_DB and os.path.exists(KUZATUV_DB):
        threading.Thread(target=prefetch_loop, name="kesh", daemon=True).start()
        log.info("rasm keshi: %s (%d kun), har %d s", CACHE_DIR, CACHE_DAYS, PREFETCH_EVERY)
    else:
        log.warning("KUZATUV_DB topilmadi — rasm keshi o'chiq: RITM o'chirgan rasmlar tahlil qilinmaydi")
    api = Api()
    done: collections.deque = collections.deque()   # oxirgi soatda tahlil qilingan vizitlar vaqti
    pause_until, pause_reason = 0.0, ""
    last_ok, last_err, last_err_at = "", "", 0.0
    last_status, last_sent = 0.0, None
    while True:
        now = time.time()
        try:
            batch = None
            if now < pause_until:
                ok, detail = False, f"{pause_reason}. Qayta urinish {hm(pause_until)} da"
            else:
                while done and now - done[0] > 3600:
                    done.popleft()
                room = MAX_PER_HOUR - len(done)
                if room <= 0:
                    ok, detail = True, f"Soatlik chegara ({MAX_PER_HOUR} ta vizit) to'ldi — navbat {hm(done[0] + 3600)} da davom etadi"
                else:
                    batch = api.claim(min(BATCH, room))
                    ok = True
                    if last_err and now - last_err_at < 1800:
                        detail = f"Ishlayapti. {hm(last_err_at)} dagi xato: {last_err}"
                    else:
                        detail = "Ishlayapti" + (f". {last_ok}" if last_ok else " — yangi rasm kutilmoqda")
            if (ok, detail) != last_sent and now - last_status > 30 or now - last_status > 300:
                api.status(ok, detail)
                last_status, last_sent = now, (ok, detail)
            if batch and batch.get("items"):
                t0 = time.time()
                try:
                    n, bad = process(api, batch)
                    done.extend([time.time()] * n)
                    last_ok = f"Oxirgi to'da {hm(t0)}: {n} ta vizit ({time.time() - t0:.0f} soniya)"
                    log.info("to'da: %d tahlil, %d xato, %.0f s", n, bad, time.time() - t0)
                    continue
                except Retry as e:
                    log.warning("to'da keyinroq: %s", e)
                    if not e.count:
                        pause_until, pause_reason = time.time() + min(max(e.delay, 60), 12 * 3600), str(e)
                    else:
                        last_err, last_err_at = str(e)[:200], time.time()
                except Exception as e:
                    log.exception("to'da: kutilmagan xato")
                    for item in batch.get("items") or []:
                        try:
                            api.fail(item["visitId"], f"kutilmagan xato: {e}", True, 300, True)
                        except Exception:
                            pass
                    last_err, last_err_at = f"kutilmagan xato: {e}"[:200], time.time()
                continue
        except Exception as e:
            log.error("navbat bilan ishlashda xato: %s", e)
        time.sleep(POLL)


def try_files(paths: list[str]) -> int:
    """API'siz sinov: har fayl alohida vizit."""
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
    visits = []
    for i, path in enumerate(paths):
        with open(path, "rb") as f:
            visits.append({"ref": f"v{i + 1}", "outletName": os.path.basename(path), "images": [shrink(f.read())]})
    t0 = time.time()
    results, model = analyse(visits, int(env("PHOTO_AI_EXPECTED", "1")), None)
    print(json.dumps({"model": model, "seconds": round(time.time() - t0), "results": results}, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--try":
        sys.exit(try_files(sys.argv[2:]))
    if len(sys.argv) > 1 and sys.argv[1] == "--prefetch-once":
        logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
        t0 = time.time()
        print(dict(prefetch_once()), f"{time.time() - t0:.0f} s", CACHE_DIR)
        sys.exit(0)
    sys.exit(main())
