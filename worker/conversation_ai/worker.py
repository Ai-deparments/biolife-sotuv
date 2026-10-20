#!/usr/bin/env python3
"""
Suhbat tahlili ishchisi (BioLife Agent AI).

API navbatidan yozuvni oladi va:
  1. ffmpeg bilan 16 kHz mono Opus (OGG) ga siqadi;
  2. Gemini (gemini-3.5-transcribe) — FAQAT nutqni matnga o'giradi: so'zlovchilarga ajratib, vaqt belgilari bilan;
  3. Claude (Max obuna, serverdagi AI shlyuzi orqali) — suhbatni 7 mezon bo'yicha baholaydi, har ballni iqtibos bilan
     asoslaydi, agentga maslahat yozadi;
  4. natijani API'ga qaytaradi → reyting, kunlik hisobot va rahbar paneli.

Bir vaqtda bitta yozuv. Gemini kvotasi tugasa yoki Claude limiti to'lsa yozuv navbatda qoladi (urinish hisoblanmaydi).
Panelga holat «AI tahlil» manbasi bo'lib boradi (SourceSynced).

Sozlamalar (muhit, ~/biolife-sales/.env):
  AI_WORKER_USER, AI_WORKER_PASS    API servis hisobi (ADMIN)
  BRIDGE_API                        API manzili (standart http://127.0.0.1:8130)
  GEMINI_API_KEY                    Google AI Studio kaliti; bo'sh bo'lsa ishchi kutadi va panelga sababini yozadi
  GEMINI_MODEL                      standart gemini-3.5-transcribe
  GEMINI_LANGUAGES                  til ishoralari, standart "uz-UZ,ru-RU" (bo'sh — avtomatik)
  GEMINI_WORD_TIMESTAMPS            1 — so'z vaqtlari (dalil uchun soniya), 0 — o'chiq (aniqlik biroz yuqori)
  GEMINI_UPLOAD                     files — Files API orqali (hujjatdagi yo'l, standart), inline — so'rov ichida base64
  GEMINI_API_URL                    standart https://generativelanguage.googleapis.com (sinov uchun almashtiriladi)
  LLM_GATEWAY_URL, LLM_GATEWAY_TOKEN  Claude shlyuzi (standart http://127.0.0.1:4195)
  CLAUDE_MODEL                      bo'sh — shlyuz standarti
  AI_MAX_PER_HOUR                   soatiga ko'pi bilan nechta suhbat (Max obunani boshqa agentlar bilan bo'lishish uchun; 60)
  AI_POLL_SEC                       navbat bo'sh bo'lsa kutish (20)
"""
from __future__ import annotations

import base64
import collections
import datetime as dt
import json
import logging
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
from zoneinfo import ZoneInfo

import requests

log = logging.getLogger("ai")


def env(name: str, default: str = "") -> str:
    v = os.environ.get(name, "")
    return v if v.strip() else default


API = env("BRIDGE_API", "http://127.0.0.1:8130").rstrip("/")
USER = env("AI_WORKER_USER")
PASS = env("AI_WORKER_PASS")
GEMINI_KEY = env("GEMINI_API_KEY")
GEMINI_MODEL = env("GEMINI_MODEL", "gemini-3.5-transcribe")
GEMINI_URL = env("GEMINI_API_URL", "https://generativelanguage.googleapis.com").rstrip("/")
GEMINI_LANGS = [x.strip() for x in env("GEMINI_LANGUAGES", "uz-UZ,ru-RU").split(",") if x.strip()]
GEMINI_WORDS = env("GEMINI_WORD_TIMESTAMPS", "1") == "1"
GEMINI_UPLOAD = env("GEMINI_UPLOAD", "files")
GATEWAY_URL = env("LLM_GATEWAY_URL", "http://127.0.0.1:4195").rstrip("/")
GATEWAY_TOKEN = env("LLM_GATEWAY_TOKEN")
CLAUDE_MODEL = env("CLAUDE_MODEL")
MAX_PER_HOUR = int(env("AI_MAX_PER_HOUR", "60"))
POLL = int(env("AI_POLL_SEC", "20"))
INLINE_LIMIT = 14 * 1024 * 1024        # base64 bilan so'rov 20 MB dan oshmasin
MAX_AUDIO_SEC = 1790                   # diarizatsiya va so'z vaqtlari bilan Gemini 30 daqiqagacha ishlaydi
TASHKENT = ZoneInfo("Asia/Tashkent")
CODES = ["salom", "ehtiyoj", "taklif", "aksiya", "yangi", "etiroz", "yakun"]
ALLOWED_FLAGS = {"not_sales_talk", "rude", "single_voice", "no_speech", "short", "unclear_audio"}


class Retry(Exception):
    """Try this recording again later. count=False: a wait (quota/limit), not the recording's fault."""
    def __init__(self, msg: str, delay: int = 0, count: bool = True):
        super().__init__(msg)
        self.delay, self.count = delay, count


class Fatal(Exception):
    """This recording cannot be analysed (e.g. not audio)."""


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

    def claim(self) -> dict | None:
        r = self.call("POST", "/api/v1/internal/recordings/claim")
        if r.status_code == 204:
            return None
        r.raise_for_status()
        return r.json()

    def result(self, rid: str, body: dict) -> None:
        r = self.call("POST", f"/api/v1/internal/recordings/{rid}/result", json=body)
        if r.status_code >= 300:
            raise RuntimeError(f"natija qabul qilinmadi {r.status_code}: {r.text[:300]}")

    def fail(self, rid: str, error: str, retryable: bool, delay: int = 0, count: bool = True) -> None:
        self.call("POST", f"/api/v1/internal/recordings/{rid}/fail",
                  json={"error": error[:900], "retryable": retryable, "delaySec": delay, "countAttempt": count})

    def status(self, ok: bool, detail: str) -> None:
        now = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
        self.call("POST", "/api/v1/internal/events/SourceSynced",
                  json={"source": "ai-worker", "title": "AI suhbat tahlili", "syncedAt": now, "ok": ok, "detail": detail[:450]})


# ------------------------------------------------------------------ 1. AUDIO

def compress(path: str) -> tuple[bytes, str]:
    """Any browser recording (webm/opus, mp4/aac, ogg …) → 16 kHz mono Opus in OGG, ~24 kbit/s (30 min ≈ 5 MB)."""
    if not os.path.isfile(path):
        raise Fatal("yozuv fayli topilmadi")
    with tempfile.TemporaryDirectory() as tmp:
        out = os.path.join(tmp, "a.ogg")
        p = subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", path, "-t", str(MAX_AUDIO_SEC), "-vn", "-ac", "1", "-ar", "16000",
                            "-c:a", "libopus", "-b:a", "24k", "-application", "voip", out], capture_output=True, text=True, timeout=300)
        if p.returncode != 0 or not os.path.isfile(out) or os.path.getsize(out) < 200:
            raise Fatal(f"ovoz faylini o'qib bo'lmadi: {p.stderr.strip()[:200]}")
        with open(out, "rb") as f:
            return f.read(), "audio/ogg"


# ------------------------------------------------------------------ 2. GEMINI: nutq → matn (faqat shu)

def _sec(offset) -> float | None:
    if offset is None:
        return None
    if isinstance(offset, (int, float)):
        return float(offset)
    m = re.match(r"^\s*([0-9.]+)s\s*$", str(offset))
    return float(m.group(1)) if m else None


def mmss(sec: float | None) -> str:
    if sec is None:
        return ""
    s = int(round(sec))
    return f"{s // 60}:{s % 60:02d}"


def parse_transcription(data: dict) -> list[dict]:
    """generateContent javobi → [{t, sec, speaker, text}]; har qism — bitta so'zlovchining navbati.
    Qismda audioTranscription (speakerLabel, words[startOffset]) va odatda text bo'ladi; text bo'lmasa so'zlar qo'shiladi.
    Agar biror qismda audioTranscription bo'lsa, faqat shunday qismlar olinadi (umumiy matn takrorlanmasin)."""
    cands = data.get("candidates") or []
    parts = ((cands[0].get("content") or {}).get("parts") or []) if cands else []
    tagged = [p for p in parts if p.get("audioTranscription") or p.get("audio_transcription")]
    out: list[dict] = []
    for part in tagged or parts:
        at = part.get("audioTranscription") or part.get("audio_transcription") or {}
        words = at.get("words") or []
        text = str(part.get("text") or "").strip() or " ".join(str(w.get("word", "")).strip() for w in words).strip()
        if not text:
            continue
        start = _sec(words[0].get("startOffset") or words[0].get("start_offset")) if words else None
        speaker = str(at.get("speakerLabel") or at.get("speaker_label") or "spk_?")
        if out and out[-1]["speaker"] == speaker and speaker != "spk_?":
            out[-1]["text"] += " " + text          # bir so'zlovchining ketma-ket qismlari bitta navbat
            continue
        out.append({"t": mmss(start), "sec": start, "speaker": speaker, "text": text})
    return out


def until_quota_reset() -> int:
    """Gemini kunlik kvotasi Tinch okeani vaqti bilan yarim tunda yangilanadi."""
    now = dt.datetime.now(ZoneInfo("America/Los_Angeles"))
    nxt = (now + dt.timedelta(days=1)).replace(hour=0, minute=5, second=0, microsecond=0)
    return int((nxt - now).total_seconds())


def gemini_error(r: requests.Response, what: str) -> Retry:
    """Gemini javobidagi xatoni tushunarli sababga va kutish vaqtiga aylantiradi."""
    body = r.text or ""
    try:
        err = (r.json().get("error") or {}) if body.strip().startswith("{") else {}
    except ValueError:
        err = {}
    msg = str(err.get("message") or body)[:220]
    low = body.lower()
    if r.status_code == 429 or err.get("status") == "RESOURCE_EXHAUSTED":
        if "perday" in low.replace(" ", "") or "per_day" in low or "daily" in low:
            return Retry("Gemini bepul kunlik limiti tugadi — yozuvlar navbatda, limit yangilanganda davom etadi", until_quota_reset(), count=False)
        delay = 0
        for d in err.get("details") or []:
            m = re.match(r"^(\d+)(?:\.\d+)?s$", str(d.get("retryDelay") or ""))
            if m:
                delay = int(m.group(1)) + 5
        delay = delay or int(r.headers.get("Retry-After") or 0) or 90
        return Retry(f"Gemini bepul limiti vaqtincha to'ldi, {delay} soniyadan keyin davom etadi", delay, count=False)
    if "location is not supported" in low or "user location" in low:
        return Retry("Gemini bu hududdan ishlamayapti (User location is not supported)", 6 * 3600, count=False)
    if r.status_code in (401, 403) or "api key not valid" in low or "api_key_invalid" in low:
        return Retry(f"Gemini kalitni qabul qilmadi ({r.status_code}) — kalitni tekshiring", 1800, count=False)
    if r.status_code >= 500:
        return Retry(f"Gemini xatosi {r.status_code} ({what})", 300)
    return Retry(f"Gemini {r.status_code} ({what}): {msg}", 900)


def gemini_headers(extra: dict | None = None) -> dict:
    h = {"x-goog-api-key": GEMINI_KEY}
    if extra:
        h.update(extra)
    return h


def upload_file(audio: bytes, mime: str, name: str) -> tuple[str, str]:
    """Files API (resumable): yuklaydi, ACTIVE bo'lishini kutadi. → (file name, file uri)"""
    try:
        r = requests.post(f"{GEMINI_URL}/upload/v1beta/files", timeout=60, json={"file": {"display_name": name}},
                          headers=gemini_headers({"X-Goog-Upload-Protocol": "resumable", "X-Goog-Upload-Command": "start",
                                                  "X-Goog-Upload-Header-Content-Length": str(len(audio)),
                                                  "X-Goog-Upload-Header-Content-Type": mime}))
        if r.status_code >= 300:
            raise gemini_error(r, "fayl yuklash")
        url = r.headers.get("x-goog-upload-url") or r.headers.get("X-Goog-Upload-URL")
        if not url:
            raise Retry("Gemini fayl yuklash manzilini bermadi", 600)
        r = requests.post(url, data=audio, timeout=300,
                          headers={"Content-Length": str(len(audio)), "X-Goog-Upload-Offset": "0", "X-Goog-Upload-Command": "upload, finalize"})
        if r.status_code >= 300:
            raise gemini_error(r, "fayl yuklash")
        f = r.json().get("file") or {}
        fname, uri = f.get("name"), f.get("uri")
        if not fname or not uri:
            raise Retry("Gemini yuklangan fayl haqida ma'lumot bermadi", 600)
        state, waited = f.get("state"), 0
        while state == "PROCESSING" and waited < 120:
            time.sleep(3)
            waited += 3
            g = requests.get(f"{GEMINI_URL}/v1beta/{fname}", headers=gemini_headers(), timeout=30)
            if g.status_code >= 300:
                raise gemini_error(g, "fayl holati")
            state = g.json().get("state")
        if state not in (None, "ACTIVE"):
            delete_file(fname)
            raise Retry(f"Gemini faylni qabul qilmadi (holat: {state})", 600)
        return fname, uri
    except requests.RequestException as e:
        raise Retry(f"Gemini bilan aloqa yo'q: {e}", 300)


def delete_file(fname: str) -> None:
    """Yozuv Google'da saqlanib qolmasin: tahlildan keyin darhol o'chiriladi (aks holda 48 soatda o'zi o'chadi)."""
    try:
        requests.delete(f"{GEMINI_URL}/v1beta/{fname}", headers=gemini_headers(), timeout=30)
    except requests.RequestException as e:
        log.warning("Gemini fayli o'chirilmadi %s: %s", fname, e)


def transcribe(audio: bytes, mime: str, rid: str) -> list[dict]:
    cfg: dict = {"diarization": True}
    if GEMINI_WORDS:
        cfg["wordTimestamp"] = True
    if GEMINI_LANGS:
        cfg["languageCodes"] = GEMINI_LANGS
    fname = None
    if GEMINI_UPLOAD == "inline":
        if len(audio) > INLINE_LIMIT:
            raise Fatal("yozuv siqilgandan keyin ham juda katta")
        media = {"inlineData": {"mimeType": mime, "data": base64.b64encode(audio).decode()}}
    else:
        fname, uri = upload_file(audio, mime, f"biolife-{rid}")
        media = {"fileData": {"fileUri": uri, "mimeType": mime}}
    url = f"{GEMINI_URL}/v1beta/models/{GEMINI_MODEL}:generateContent"
    try:
        for attempt in (1, 2):
            body = {"contents": [{"parts": [media]}], "generationConfig": {"audioTranscriptionConfig": cfg}}
            try:
                r = requests.post(url, headers=gemini_headers(), json=body, timeout=600)
            except requests.RequestException as e:
                raise Retry(f"Gemini bilan aloqa yo'q: {e}", 300)
            if r.status_code == 200:
                return parse_transcription(r.json())
            if r.status_code == 400 and "language" in r.text.lower() and "languageCodes" in cfg and attempt == 1:
                log.warning("Gemini til ishorasini qabul qilmadi, tilni o'zi aniqlaydi: %s", r.text[:300])
                cfg.pop("languageCodes")
                continue
            raise gemini_error(r, "matnga o'girish")
        raise Retry("Gemini javob bermadi", 900)
    finally:
        if fname:
            delete_file(fname)


# ------------------------------------------------------------------ 3. CLAUDE: baholash

SYSTEM = """Sen BioLife kompaniyasi (IMIR-TRADE GROUP; ichimliklar: BIOLIFE suvi, FRUCTIS limonadlari, Moxito, Time Tea sovuq choyi, Sitro) savdo bo'limining suhbat tahlilchisisan.
Senga savdo agentining do'kondagi suhbati matni beriladi. Bu nutqni matnga o'girish natijasi: har gap oldida vaqt [m:ss] va so'zlovchi belgisi (masalan spk:0, spk:1). Suhbat o'zbek va rus tillarida aralash bo'lishi mumkin.
Matnda o'girish xatolari bo'lishi mumkin: ma'noni kontekstdan tushun, lekin aytilmagan narsani o'ylab topma.
Brend nomlari ko'pincha buzilib yoziladi — ma'nosiga qarab tani: FRUCTIS → «Frutty», «Fruktis», «Фруктис»; Time Tea → «Tea», «Taym Ti», «Тайм Ти»; Moxito → «Mojito», «Мохито»; BIOLIFE → «BioLife», «Bio Layf», «Биолайф»; Sitro → «Citro», «Ситро».

Vazifa:
1. Qaysi so'zlovchi agent ekanini aniqla: u o'zini yoki firmani tanishtiradi, mahsulot va narx taklif qiladi, buyurtma so'raydi. Qolganlar — sotuvchi yoki do'kon egasi.
2. Agentni 7 mezon bo'yicha baholab, har biriga 0 dan qavsdagi maksimal ballgacha butun son qo'y:
   salom (10) — salomlashdi, o'zini va firmani tanishtirdi, kelish maqsadini aytdi;
   ehtiyoj (20) — do'kondagi qoldiq, nima yaxshi sotilayotgani, oxirgi yetkazish haqida savollar berdi;
   taklif (20) — aniq mahsulotlarni nomma-nom taklif qildi (hajm, ta'm yoki narx bilan);
   aksiya (15) — joriy aksiya shartlarini to'liq aytdi;
   yangi (10) — yangi mahsulotni ko'rsatdi yoki tanishtirdi;
   etiroz (15) — «olmayman», «qimmat», «sotilmayapti» kabi e'tirozga dalil bilan javob berdi; e'tiroz bo'lmagan bo'lsa, taklifni qanchalik ishonchli asoslaganiga qarab bahola;
   yakun (10) — miqdor va yetkazish kunini aniq kelishib, buyurtma bilan yakunladi.
   Matnda yo'q narsa uchun ball berma.
3. Muhim ballar uchun dalil keltir: suhbatdan qisqa iqtibos va uning vaqti (ko'pi bilan 8 ta).
4. Agentga bitta aniq, amaliy maslahat yoz: keyingi vizitda nima qilsin (1–2 gap).
5. Qisqa xulosa yoz: suhbatda nima bo'ldi (1–2 gap).
6. Bayroqlar, faqat haqiqatan bo'lsa: "not_sales_talk" — bu savdo suhbati emas; "rude" — agent qo'pol gapirdi; "single_voice" — faqat bitta odam gapiryapti; "unclear_audio" — matn juda buzuq, baholash ishonchsiz.

Javobni FAQAT bitta JSON obyekt ko'rinishida ber, oldidan ham, keyin ham hech narsa yozma:
{"agentSpeaker":"spk:0","criteria":{"salom":0,"ehtiyoj":0,"taklif":0,"aksiya":0,"yangi":0,"etiroz":0,"yakun":0},"promoMentioned":false,"summary":"...","advice":"...","flags":[],"evidence":[{"t":"0:04","who":"agent","text":"...","mark":"good","criterion":"salom"}]}
"who" — "agent" yoki "sotuvchi"; "mark" — "good" (yaxshi), "bad" (xato yoki o'tkazib yuborilgan), "note" (e'tibor bering); "criterion" — mezon kodi yoki null. summary va advice o'zbek tilida, lotin yozuvida."""

WEIGHTS = {"salom": 10, "ehtiyoj": 20, "taklif": 20, "aksiya": 15, "yangi": 10, "etiroz": 15, "yakun": 10}


def claude(system: str, prompt: str) -> tuple[str, str]:
    """Shlyuz: 200 {text, model}; 502 {error} — CLI xabari (limit, login muddati, vaqt tugashi)."""
    body = {"system": system, "prompt": prompt, "timeoutMs": 160_000, "maxBudgetUsd": 0.5}
    if CLAUDE_MODEL:
        body["model"] = CLAUDE_MODEL
    try:
        r = requests.post(f"{GATEWAY_URL}/v1/complete", headers={"Authorization": f"Bearer {GATEWAY_TOKEN}"}, json=body, timeout=200)
    except requests.RequestException as e:
        raise Retry(f"Claude shlyuziga ulanib bo'lmadi: {e}", 300)
    if r.status_code == 200:
        d = r.json()
        return str(d.get("text") or ""), str(d.get("model") or CLAUDE_MODEL or "claude")
    try:
        err = str(r.json().get("error") or r.text)[:300]
    except ValueError:
        err = r.text[:300]
    if r.status_code == 401:
        raise Retry("Claude shlyuzi kalitni qabul qilmadi (LLM_GATEWAY_TOKEN)", 1800, count=False)
    if re.search(r"authenticat|oauth|log ?in|session expired", err, re.I):
        raise Retry(f"Claude obunasiga kirish muddati tugagan — serverda qayta login kerak ({err[:120]})", 1800, count=False)
    if re.search(r"usage limit|limit reached|hit your limit|rate.?limit|quota|too many requests|overloaded|resets", err, re.I):
        raise Retry(f"Claude obuna limiti to'ldi: {err[:140]}", 1800, count=False)
    if r.status_code == 413:
        raise Fatal("suhbat matni Claude shlyuzi uchun juda katta")
    raise Retry(f"Claude shlyuzi {r.status_code}: {err}", 600)


def extract_json(text: str) -> dict:
    t = text.strip()
    t = re.sub(r"^```(?:json)?\s*|\s*```$", "", t)
    a, b = t.find("{"), t.rfind("}")
    if a < 0 or b <= a:
        raise ValueError("javobda JSON yo'q")
    return json.loads(t[a:b + 1])


def normalize(d: dict, speakers: set[str]) -> dict:
    crit_in = d.get("criteria") if isinstance(d.get("criteria"), dict) else {}
    criteria = {}
    for c in CODES:
        try:
            v = int(round(float(crit_in.get(c, 0))))
        except (TypeError, ValueError):
            v = 0
        criteria[c] = max(0, min(WEIGHTS[c], v))
    evidence = []
    for e in (d.get("evidence") or [])[:8]:
        if not isinstance(e, dict) or not str(e.get("text") or "").strip():
            continue
        who = "agent" if str(e.get("who")).lower().startswith("agent") else "sotuvchi"
        mark = str(e.get("mark") or "note").lower()
        crit = e.get("criterion") if e.get("criterion") in CODES else None
        evidence.append({"t": str(e.get("t") or "")[:8], "who": who, "text": str(e["text"]).strip()[:240],
                         "mark": mark if mark in ("good", "bad", "note") else "note", "criterion": crit})
    flags = [f for f in (d.get("flags") or []) if isinstance(f, str) and f in ALLOWED_FLAGS]
    agent = str(d.get("agentSpeaker") or "")
    return {"criteria": criteria, "promoMentioned": bool(d.get("promoMentioned")), "evidence": evidence, "flags": flags,
            "agentSpeaker": agent if agent in speakers else (agent or None),
            "summary": str(d.get("summary") or "").strip()[:900], "advice": str(d.get("advice") or "").strip()[:900]}


def analyse(job: dict, utterances: list[dict]) -> tuple[dict, str]:
    dur = int(job.get("durationSec") or 0)
    lines = "\n".join(f"[{u['t'] or '?'}] {u['speaker']}: {u['text']}" for u in utterances)
    prompt = (f"Agent: {job.get('agentName') or 'nomi noma`lum'}\n"
              f"Suhbat davomiyligi: {dur // 60}:{dur % 60:02d}\n"
              f"Joriy aksiya: {job.get('promoText') or 'berilmagan — har qanday aksiya, chegirma yoki bonus aytilganini bahola'}\n"
              f"Yangi mahsulot: {job.get('newProductText') or 'berilmagan — yangi deb tanishtirilgan mahsulotni bahola'}\n\n"
              f"Suhbat matni:\n{lines}")
    speakers = {u["speaker"] for u in utterances}
    text, model = claude(SYSTEM, prompt)
    try:
        return normalize(extract_json(text), speakers), model
    except (ValueError, json.JSONDecodeError):
        log.warning("Claude JSON bermadi, qayta so'ralmoqda")
        text, model = claude(SYSTEM, prompt + "\n\nFaqat JSON obyekt bilan javob ber, boshqa hech narsa yozma.")
        try:
            return normalize(extract_json(text), speakers), model
        except (ValueError, json.JSONDecodeError) as e:
            raise Retry(f"Claude javobi JSON emas: {e}", 600)


# ------------------------------------------------------------------ 4. BIR YOZUV

def process(api: Api, job: dict) -> int:
    rid = job["id"]
    audio, mime = compress(job["filePath"])
    utterances = transcribe(audio, mime, rid)
    words = sum(len(u["text"].split()) for u in utterances)
    dur = int(job.get("durationSec") or 0)
    flags: list[str] = []
    if dur < 30:
        flags.append("short")
    if words < 5:
        result = {"transcript": utterances, "criteria": {c: 0 for c in CODES}, "promoMentioned": False, "flags": flags + ["no_speech"],
                  "summary": "Yozuvda tushunarli nutq topilmadi.", "advice": "Suhbatni boshidan oxirigacha yozing: telefonni ochiq va yaqin tuting.",
                  "evidence": [], "agentSpeaker": None, "models": {"stt": GEMINI_MODEL}}
        api.result(rid, result)
        return 0
    if len({u["speaker"] for u in utterances}) == 1 and dur >= 45:
        flags.append("single_voice")
    a, model = analyse(job, utterances)
    result = {"transcript": utterances, "language": None, "agentSpeaker": a["agentSpeaker"], "criteria": a["criteria"],
              "promoMentioned": a["promoMentioned"], "flags": sorted(set(flags + a["flags"])), "summary": a["summary"], "advice": a["advice"],
              "evidence": a["evidence"], "models": {"stt": GEMINI_MODEL, "llm": model}}
    api.result(rid, result)
    return sum(a["criteria"].values())


def hm(ts: float) -> str:
    return dt.datetime.fromtimestamp(ts, TASHKENT).strftime("%H:%M")


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    if not USER or not PASS:
        log.error("AI_WORKER_USER / AI_WORKER_PASS sozlanmagan")
        return 2
    log.info("ishchi: STT %s (%s), Claude shlyuzi %s, soatiga %d ta", GEMINI_MODEL, GEMINI_UPLOAD, GATEWAY_URL, MAX_PER_HOUR)
    api = Api()
    done: collections.deque = collections.deque()   # oxirgi soatda tahlil qilinganlar vaqti
    pause_until, pause_reason = 0.0, ""              # kvota yoki limit: hech bir yozuv olinmaydi
    last_ok, last_err, last_err_at = "", "", 0.0
    last_status, last_sent = 0.0, None
    while True:
        now = time.time()
        try:
            job = None
            if not GEMINI_KEY:
                ok, detail = False, "Gemini API kaliti kiritilmagan — suhbatlar navbatda kutyapti"
            elif not GATEWAY_TOKEN:
                ok, detail = False, "Claude shlyuzi kaliti (LLM_GATEWAY_TOKEN) kiritilmagan"
            elif now < pause_until:
                ok, detail = False, f"{pause_reason}. Qayta urinish {hm(pause_until)} da"
            else:
                while done and now - done[0] > 3600:
                    done.popleft()
                if len(done) >= MAX_PER_HOUR:
                    ok, detail = True, f"Soatlik chegara ({MAX_PER_HOUR} ta suhbat) to'ldi — navbat {hm(done[0] + 3600)} da davom etadi"
                else:
                    job = api.claim()
                    ok = True
                    if last_err and now - last_err_at < 1800:
                        detail = f"Ishlayapti. {hm(last_err_at)} dagi xato: {last_err}"
                    else:
                        detail = "Ishlayapti" + (f". {last_ok}" if last_ok else " — yangi suhbat kutilmoqda")
            if (ok, detail) != last_sent and now - last_status > 30 or now - last_status > 300:
                api.status(ok, detail)
                last_status, last_sent = now, (ok, detail)
            if job:
                t0 = time.time()
                try:
                    score = process(api, job)
                    done.append(time.time())
                    last_ok = f"Oxirgi tahlil {hm(t0)}: {score} ball ({time.time() - t0:.0f} soniya)"
                    log.info("yozuv %s: %d ball, %.0f s", job["id"], score, time.time() - t0)
                    continue                                   # navbatda yana bo'lishi mumkin
                except Retry as e:
                    log.warning("yozuv %s keyinroq: %s", job["id"], e)
                    api.fail(job["id"], str(e), True, e.delay, e.count)
                    if not e.count:                            # yozuvning aybi emas: hamma yozuv kutadi
                        pause_until, pause_reason = time.time() + min(max(e.delay, 60), 12 * 3600), str(e)
                    else:
                        last_err, last_err_at = str(e), time.time()
                except Fatal as e:
                    log.error("yozuv %s tahlil qilinmaydi: %s", job["id"], e)
                    api.fail(job["id"], str(e), False)
                    last_err, last_err_at = str(e), time.time()
                except Exception as e:                         # kutilmagan: navbatga qaytadi, urinish hisoblanadi
                    log.exception("yozuv %s: kutilmagan xato", job["id"])
                    api.fail(job["id"], f"kutilmagan xato: {e}", True, 0, True)
                    last_err, last_err_at = f"kutilmagan xato: {e}"[:200], time.time()
                continue
        except Exception as e:
            log.error("navbat bilan ishlashda xato: %s", e)
        time.sleep(POLL)


if __name__ == "__main__":
    sys.exit(main())
