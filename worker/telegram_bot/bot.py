#!/usr/bin/env python3
"""
Rahbar boti (BioLife Agent AI) — savdo rahbariyati uchun Telegram'dagi AI yordamchi.

Bot buyruqli menyu emas, savolga javob beradigan agent: rahbar oddiy so'z bilan so'raydi («bugun kim kech keldi?»,
«Samarqandda polka qanday?», «Suhrobning shu haftasi»), bot API'dan kerakli ma'lumotni yig'adi va Claude (Max obuna,
serverdagi `claude` CLI; ishlamasa `nova-llm-gateway`) shu ma'lumot asosida qisqa javob yozadi. Pastdagi yirik tugmalar — tez hisobotlar
(model chaqirilmaydi, darrov keladi):

  📊 Bugun · 📅 Kecha · 📷 Polka nazorati · 👥 Agentlar · ⏰ Ish vaqti · 🗺 Hududlar · 🌐 Panel

Kunlik (18:30) va haftalik (dushanba 09:00) hisobotni API o'zi shu bot orqali BIOLIFE_REPORT_CHAT_ID ga yuboradi:
API faqat sendMessage/sendDocument ishlatadi, bot esa getUpdates — bitta token ikkalasiga yetadi.

Kirish: faqat BOT_ALLOWED_IDS dagi Telegram foydalanuvchilari va BIOLIFE_REPORT_CHAT_ID dagi chatlar (guruh bo'lsa —
botni eslatganda yoki unga javob qaytarganda). Boshqalarga bot ularning ID raqamini aytadi, ma'lumot bermaydi.

Sozlamalar (muhit, ~/biolife-sales/.env):
  BOT_TOKEN                         bo'sh bo'lsa BIOLIFE_REPORT_BOT_TOKEN ishlatiladi
  BOT_ALLOWED_IDS                   ruxsat etilgan Telegram foydalanuvchi ID'lari, vergul bilan
  BIOLIFE_REPORT_CHAT_ID            hisobot chatlari (ular ham botdan so'ray oladi)
  AI_WORKER_USER, AI_WORKER_PASS    API servis hisobi (ADMIN; ishchilar bilan bir xil)
  BRIDGE_API                        API manzili (standart http://127.0.0.1:8130)
  CLAUDE_CLI                        `claude` CLI (ubuntu foydalanuvchisining obuna logini; rasm ishchisi bilan bir xil)
  BOT_CLAUDE_MODEL                  standart sonnet
  LLM_GATEWAY_URL, LLM_GATEWAY_TOKEN  zaxira yo'l: CLI ishlamasa Claude shlyuzi (standart http://127.0.0.1:4195)
  BIOLIFE_PUBLIC_URL_METRICS        tunnel manzili (🌐 Panel tugmasi uchun)
  PHOTO_CACHE_DIR                   polka rasmlari keshi (rasm ishchisi bilan bir xil)

Sinov (Telegram'siz): python3 bot.py --ask "bugun nechta agent ishladi?"
                      python3 bot.py --show bugun|kecha|polka|agentlar|vaqt|hududlar
"""
from __future__ import annotations

import collections
import concurrent.futures
import datetime as dt
import hashlib
import html
import json
import logging
import os
import re
import signal
import subprocess
import sys
import tempfile
import threading
import time
from zoneinfo import ZoneInfo

import requests

log = logging.getLogger("bot")


def env(name: str, default: str = "") -> str:
    v = os.environ.get(name, "")
    return v if v.strip() else default


def id_list(text: str) -> set[str]:
    return {x for x in re.split(r"[,;\s]+", text or "") if x.strip()}


TOKEN = env("BOT_TOKEN", env("BIOLIFE_REPORT_BOT_TOKEN")).strip()
ALLOWED_USERS = id_list(env("BOT_ALLOWED_IDS"))
REPORT_CHATS = id_list(env("BIOLIFE_REPORT_CHAT_ID"))
API = env("BRIDGE_API", "http://127.0.0.1:8130").rstrip("/")
USER = env("AI_WORKER_USER")
PASS = env("AI_WORKER_PASS")
GATEWAY_URL = env("LLM_GATEWAY_URL", "http://127.0.0.1:4195").rstrip("/")
GATEWAY_TOKEN = env("LLM_GATEWAY_TOKEN")
CLAUDE_MODEL = env("BOT_CLAUDE_MODEL", "sonnet")
CLI = env("CLAUDE_CLI", "claude")
PUBLIC_URL_METRICS = env("BIOLIFE_PUBLIC_URL_METRICS")
CACHE_DIR = env("PHOTO_CACHE_DIR", os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "data", "photo-cache")))
TG = "https://api.telegram.org"
TASHKENT = ZoneInfo("Asia/Tashkent")
LIMIT = 4000
LINE = "━━━━━━━━━━━━━━━━━━━━"
MONTHS = ["yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul", "avgust", "sentabr", "oktabr", "noyabr", "dekabr"]

B_TODAY, B_YESTERDAY, B_PHOTO, B_AGENTS, B_HOURS, B_REGIONS, B_PANEL = (
    "📊 Bugun", "📅 Kecha", "📷 Polka nazorati", "👥 Agentlar", "⏰ Ish vaqti", "🗺 Hududlar", "🌐 Panel")
KEYBOARD = {"keyboard": [[{"text": B_TODAY}, {"text": B_YESTERDAY}], [{"text": B_PHOTO}, {"text": B_AGENTS}],
                         [{"text": B_HOURS}, {"text": B_REGIONS}], [{"text": B_PANEL}]],
            "resize_keyboard": True, "is_persistent": True, "input_field_placeholder": "Savolingizni yozing…"}


# ------------------------------------------------------------------ yordamchilar

def today() -> dt.date:
    return dt.datetime.now(TASHKENT).date()


def week_start(d: dt.date) -> dt.date:
    return d - dt.timedelta(days=d.weekday())


def day_name(d: dt.date) -> str:
    return f"{d.day}-{MONTHS[d.month - 1]}"


def period_name(a: dt.date, b: dt.date) -> str:
    if a == b:
        return day_name(a)
    if a.month == b.month:
        return f"{a.day}–{b.day}-{MONTHS[b.month - 1]}"
    return f"{day_name(a)} – {day_name(b)}"


def n(x) -> str:
    """1234567 → «1 234 567»."""
    try:
        return f"{int(round(float(x))):,}".replace(",", " ")
    except (TypeError, ValueError):
        return "—"


def pct(a, b) -> int:
    return round(100 * a / b) if b else 0


def money(x) -> str:
    x = float(x or 0)
    if x >= 1e9:
        return f"{x / 1e9:.2f}".replace(".", ",") + " mlrd so'm"
    if x >= 1e6:
        return f"{x / 1e6:.1f}".replace(".", ",") + " mln so'm"
    return n(x) + " so'm"


def e(s) -> str:
    return html.escape(str(s if s is not None else "—"), quote=False)


def hm_local(iso: str | None) -> str:
    if not iso:
        return "—"
    try:
        return dt.datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone(TASHKENT).strftime("%H:%M")
    except ValueError:
        return "—"


# ------------------------------------------------------------------ API

class Api:
    def __init__(self) -> None:
        self.s = requests.Session()
        self.token: str | None = None
        self.lock = threading.Lock()

    def login(self) -> None:
        r = self.s.post(f"{API}/api/v1/auth/login", json={"username": USER, "password": PASS}, timeout=20)
        if r.status_code != 200:
            raise RuntimeError(f"API login {r.status_code}: {r.text[:200]}")
        self.token = r.json()["accessToken"]

    def get(self, path: str, **params):
        with self.lock:
            if not self.token:
                self.login()
            token = self.token
        for attempt in (1, 2):
            r = self.s.get(f"{API}{path}", params={k: v for k, v in params.items() if v is not None},
                           headers={"Authorization": f"Bearer {token}"}, timeout=60)
            if r.status_code == 401 and attempt == 1:
                with self.lock:
                    self.login()
                    token = self.token
                continue
            r.raise_for_status()
            return r.json()

    def overview(self, d: dt.date) -> dict:
        return self.get("/api/v1/panel/analytics/overview", date=d.isoformat())

    def agents(self, a: dt.date, b: dt.date) -> list:
        return self.get("/api/v1/panel/analytics/agents", **{"from": a.isoformat(), "to": b.isoformat()})

    def work_hours(self, a: dt.date, b: dt.date) -> list:
        return self.get("/api/v1/panel/analytics/work-hours", **{"from": a.isoformat(), "to": b.isoformat()})

    def regions(self, a: dt.date, b: dt.date) -> list:
        return self.get("/api/v1/panel/visits/regions", **{"from": a.isoformat(), "to": b.isoformat()})

    def visit_agents(self, d: dt.date) -> list:
        return self.get("/api/v1/panel/visits/agents", date=d.isoformat())

    def visits(self, d: dt.date, kind: str, size: int = 200, page: int = 0, agent_id: str | None = None) -> dict:
        return self.get("/api/v1/panel/visits", date=d.isoformat(), kind=kind, size=size, page=page, agentId=agent_id)

    def series(self, agent_id: str, a: dt.date, b: dt.date) -> dict:
        return self.get(f"/api/v1/panel/analytics/agents/{agent_id}", **{"from": a.isoformat(), "to": b.isoformat()})

    def sources(self) -> list:
        return self.get("/api/v1/panel/sources")

    def conversations(self) -> dict:
        return self.get("/api/v1/panel/conversations/stats")

    def photo_settings(self) -> dict:
        return self.get("/api/v1/panel/visits/photo-settings")


def public_url() -> str | None:
    if not PUBLIC_URL_METRICS:
        return None
    try:
        host = requests.get(PUBLIC_URL_METRICS, timeout=5).json().get("hostname")
        return f"https://{host}" if host else None
    except (requests.RequestException, ValueError):
        return None


# ------------------------------------------------------------------ tez hisobotlar (modelsiz)

VERDICTS = [("low", "🟡", "Kam (BioLife bor, standartdan kam)"), ("none", "🔴", "BioLife yo'q"),
            ("unclear", "🌫", "Sanab bo'lmadi (xira yoki uzoqdan)"), ("not_shelf", "🚪", "Polka emas (tashqi ko'rinish, selfi)")]


def day_report(api: Api, d: dt.date) -> str:
    o = api.overview(d)
    if not o.get("started") and not o.get("planned"):
        return f"📊 <b>{day_name(d)}</b>\n{LINE}\nBu kunda vizit yo'q (dam olish kuni yoki ma'lumot hali kelmagan)."
    t = [f"📊 <b>Kun holati · {day_name(d)}</b>", LINE,
         f"👥 Ishlagan agentlar: <b>{n(o.get('agentsActive'))}</b> / {n(o.get('agentsTotal'))}",
         f"🗺 Vizitlar (bajarildi / reja): <b>{n(o.get('closed'))}</b> / {n(o.get('planned'))} · <b>{pct(o.get('closed', 0), o.get('planned', 0))}%</b>",
         f"✅ Dalillar to'liq (joyida + rasm + buyurtma): <b>{n(o.get('proofsCompleteRate'))}%</b>",
         f"⚠️ Shubhali vizit (joyidan uzoq yoki eski rasm): <b>{n(o.get('suspect'))}</b>",
         f"💰 Buyurtmalar: <b>{money(o.get('salesUzs'))}</b>",
         "",
         f"📷 Polka rasmi standarti (AI tekshirganlardan): <b>{n(o.get('photosOkRate'))}%</b> · tekshirildi <b>{n(o.get('photosTotal'))}</b>",
         f"🎙 Suhbatlar tahlili: <b>{n(o.get('conversations'))}</b>" + (f" · o'rtacha ball <b>{n(o.get('avgScore'))}</b>" if o.get("conversations") else ""),
         "",
         f"⏰ Kech boshlagan (09:30 dan keyin): <b>{n(o.get('lateStarts'))}</b>",
         f"🏃 Erta tugatgan (17:30 dan oldin): <b>{n(o.get('earlyFinishes'))}</b>"]
    if d == today() and dt.datetime.now(TASHKENT).hour < 18:
        t.append("<i>Kun hali tugamagan — «erta tugatgan» soni kechqurun aniq bo'ladi.</i>")
    return "\n".join(t)


def photo_stats(api: Api, d: dt.date) -> dict:
    first = api.visits(d, "photobad", size=200)
    items = list(first.get("items") or [])
    total_bad = int(first.get("total") or 0)
    page = 1
    while len(items) < min(total_bad, 1000):
        more = api.visits(d, "photobad", size=200, page=page).get("items") or []
        if not more:
            break
        items += more
        page += 1
    counts = first.get("counts") or {}
    by = collections.Counter((v.get("photoAi") or {}).get("verdict") for v in items)
    per_agent: dict = {}
    for v in items:
        if (v.get("photoAi") or {}).get("verdict") == "none":
            a = per_agent.setdefault(v.get("agentId"), {"name": v.get("agentName"), "region": v.get("region"), "n": 0})
            a["n"] += 1
    return {"withPhoto": int(counts.get("withphoto") or 0), "ok": int(counts.get("photook") or 0), "bad": total_bad,
            "by": by, "items": items, "noneAgents": sorted(per_agent.values(), key=lambda a: -a["n"])}


def photo_report(api: Api, d: dt.date) -> tuple[str, list[dict]]:
    s = photo_stats(api, d)
    checked = s["ok"] + s["bad"]
    if not checked:
        return (f"📷 <b>Polka nazorati · {day_name(d)}</b>\n{LINE}\nBu kunda AI hali rasm tekshirmagan.", [])
    t = [f"📷 <b>Polka nazorati · {day_name(d)}</b>", LINE,
         f"🖼 Rasmli vizitlar: <b>{n(s['withPhoto'])}</b>",
         f"🤖 AI tekshirdi (har agentdan teng namuna): <b>{n(checked)}</b> · <b>{pct(checked, s['withPhoto'])}%</b>",
         "",
         f"✅ Standart bajarilgan: <b>{n(s['ok'])}</b> · <b>{pct(s['ok'], checked)}%</b>"]
    for code, icon, label in VERDICTS:
        t.append(f"{icon} {label}: <b>{n(s['by'].get(code, 0))}</b> · {pct(s['by'].get(code, 0), checked)}%")
    if s["noneAgents"]:
        t += ["", "⚠️ <b>BioLife yo'q rasmlar eng ko'p:</b>"]
        for a in s["noneAgents"][:7]:
            t.append(f"• {e(a['name'])} ({e(a['region'])}): <b>{a['n']}</b> ta")
    examples = [v for v in s["items"] if (v.get("photoAi") or {}).get("verdict") in ("none", "low") and v.get("photoUrls")][:3]
    if examples:
        t += ["", "👇 Namuna rasmlar pastda."]
    return "\n".join(t), examples


def agents_report(api: Api) -> str:
    t = today()
    a, b = week_start(t), t
    if t.weekday() == 6:                                          # yakshanba: shu haftaning du–sha
        b = t - dt.timedelta(days=1)
    elif t.weekday() == 0 and dt.datetime.now(TASHKENT).hour < 12:  # dushanba ertalab: o'tgan hafta
        a = a - dt.timedelta(days=7)
        b = a + dt.timedelta(days=5)
    rows = [r for r in api.agents(a, b) if r.get("planned")]
    if not rows:
        return f"👥 <b>Agentlar · {period_name(a, b)}</b>\n{LINE}\nBu davrda reja yo'q."
    worked = [r for r in rows if r.get("daysWorked")]
    absent = [r for r in rows if not r.get("daysWorked")]
    # rejadan tashqari vizit foizni 100 dan oshiradi (15/1 = 1500%): reytingda 100 da kesiladi, rejasi kichiklar chetda
    best = sorted([r for r in worked if r.get("planned", 0) >= 10],
                  key=lambda r: (-min(r.get("completionRate", 0), 100), -r.get("closed", 0)))[:5]
    top_sales = sorted(worked, key=lambda r: -r.get("salesUzs", 0))[:5]
    weak = sorted([r for r in worked if r.get("planned", 0) >= 10], key=lambda r: (r.get("completionRate", 0), r.get("closed", 0)))[:7]
    t = [f"👥 <b>Agentlar · {period_name(a, b)}</b>", LINE,
         f"Rejasi bor agentlar: <b>{len(rows)}</b>, ishlaganlar: <b>{len(worked)}</b>", "",
         "🥇 <b>Rejani eng yaxshi bajarganlar:</b>"]
    t += [f"• {e(r['name'])} ({e(r.get('region'))}): <b>{r.get('completionRate')}%</b> · {n(r.get('closed'))}/{n(r.get('planned'))}" for r in best]
    t += ["", "💰 <b>Buyurtma summasi bo'yicha eng yaxshilar:</b>"]
    t += [f"• {e(r['name'])} ({e(r.get('region'))}): <b>{money(r.get('salesUzs'))}</b>" for r in top_sales]
    t += ["", "⚠️ <b>E'tibor kerak (reja kam bajarilgan):</b>"]
    t += [f"• {e(r['name'])} ({e(r.get('region'))}): <b>{r.get('completionRate')}%</b> · {n(r.get('closed'))}/{n(r.get('planned'))}" for r in weak]
    if absent:
        t += ["", f"🚫 <b>Rejasi bor, lekin bu davrda ishga chiqmagan: {len(absent)}</b>"]
        t += [f"• {e(r['name'])} ({e(r.get('region'))})" for r in absent[:10]]
        if len(absent) > 10:
            t.append(f"<i>… yana {len(absent) - 10} ta — to'liq ro'yxat panelda</i>")
    return "\n".join(t)


def hours_report(api: Api, d: dt.date) -> str:
    rows = [r for r in api.work_hours(d, d) if r.get("daysWorked")]
    if not rows:
        return f"⏰ <b>Ish vaqti · {day_name(d)}</b>\n{LINE}\nBu kunda faollik yo'q."
    late = sorted([r for r in rows if r.get("lateStarts")], key=lambda r: r.get("avgFirstActivity") or "", reverse=True)
    early = sorted([r for r in rows if r.get("earlyFinishes")], key=lambda r: r.get("avgLastActivity") or "")
    t = [f"⏰ <b>Ish vaqti · {day_name(d)}</b>", LINE,
         f"Ishga chiqqan agentlar: <b>{len(rows)}</b>", f"Kech boshlaganlar (09:30 dan keyin): <b>{len(late)}</b>"]
    t += [f"• {e(r['name'])} ({e(r.get('region'))}): birinchi vizit <code>{e(r.get('avgFirstActivity'))}</code>" for r in late[:10]]
    if d == today() and dt.datetime.now(TASHKENT).hour < 18:
        t += ["", "<i>Erta tugatganlar ro'yxati kun oxirida (18:00 dan keyin) aniq bo'ladi.</i>"]
    else:
        t += ["", f"Erta tugatganlar (17:30 dan oldin): <b>{len(early)}</b>"]
        t += [f"• {e(r['name'])} ({e(r.get('region'))}): oxirgi vizit <code>{e(r.get('avgLastActivity'))}</code>" for r in early[:10]]
    return "\n".join(t)


def regions_report(api: Api, d: dt.date) -> str:
    rows = [r for r in api.regions(d, d) if r.get("planned") or r.get("visits")]
    if not rows:
        return f"🗺 <b>Hududlar · {day_name(d)}</b>\n{LINE}\nBu kunda vizit yo'q."
    rows.sort(key=lambda r: -r.get("coveragePct", 0))
    t = [f"🗺 <b>Hududlar · {day_name(d)}</b>", LINE, "<i>Qamrov — rejadagi do'konlarning necha foiziga borildi.</i>", ""]
    for r in rows:
        t.append(f"📍 <b>{e(r['region'])}</b>: qamrov <b>{r.get('coveragePct')}%</b> ({n(r.get('inPlanVisits'))}/{n(r.get('planned'))}) · "
                 f"buyurtma <b>{r.get('orderRatePct')}%</b> · agent {r.get('agentsActive')}/{r.get('agents')}")
    return "\n".join(t)


# ------------------------------------------------------------------ AI javob

SYSTEM = """Sen BioLife (IMIR-TRADE GROUP, ichimliklar ishlab chiqaruvchi) savdo bo'limining AI yordamchisisan — «Agent AI» tizimi.
Savol beruvchi — savdo rahbariyati. Senga tizimdan olingan haqiqiy ma'lumot (JSON va jadvallar) beriladi; javobni FAQAT shu ma'lumotdan ber.
Qoidalar:
- Ma'lumotda yo'q narsani o'ylab topma. Kerakli ma'lumot berilmagan bo'lsa, ochiq ayt va qaysi tugma yoki savol yordam berishini qisqa ko'rsat.
- Savol qaysi tilda bo'lsa (o'zbekcha lotin yoki ruscha), shu tilda javob ber. O'zbekcha — lotin yozuvida.
- Javob Telegram uchun: faqat <b>, <i>, <code> HTML teglari. Markdown (**, #, jadval) ishlatma.
- Qisqa va aniq: birinchi qatorda asosiy javob, keyin kerak bo'lsa emoji bilan boshlanadigan qatorlar ro'yxati. Raqamlarni <b>qalin</b> yoz, vaqtni <code>09:45</code> ko'rinishida. Pul — so'mda, katta sonlarni «1,2 mlrd so'm» yoki «350 mln so'm» deb yoz.
- Agent nomini ma'lumotdagidek yoz. Bir nechta agent mos kelsa, hammasini ko'rsat.
- Atamalar: «qamrov» — rejadagi do'konlarga borilgan ulush; «dalillar to'liq» — vizit joyida, rasmli va buyurtmali; «polka standarti» — AI tekshirgan rasmlardan BioLife yuzlari standartga yetganlari; AI rasmlarning faqat bir qismini (har agentdan teng namuna) tekshiradi.
- Odatda 15 qatordan oshirma; ro'yxat so'ralsa, 20 qatorgacha."""

CYR = dict(zip("абвгдеёжзийклмнопрстуфхцчшщъыьэюяқғҳў",
               ["a", "b", "v", "g", "d", "e", "yo", "j", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u", "f", "x", "ts",
                "ch", "sh", "sh", "", "i", "", "e", "yu", "ya", "q", "g", "h", "o"]))
STOP = {"agent", "агент", "tp", "тп", "ta", "ning", "nima", "qanday", "bugun", "kecha", "hafta", "haftasi", "shu", "kim", "nechta",
        "qancha", "uchun", "bilan", "haqida", "polka", "rasm", "rasmlar", "hudud", "viloyat", "jami", "boyicha", "bo'yicha"}


def latin(s: str) -> str:
    s = s.lower().replace("ʻ", "'").replace("`", "'").replace("‘", "'").replace("’", "'")
    return "".join(CYR.get(ch, ch) for ch in s)


def norm(w: str) -> str:
    """Imlo farqini yo'qotish: Suhrob = Suxrob = Сухроб, Samarqand = Самарканд."""
    w = w.replace("'", "")
    for a, b in (("kh", "h"), ("x", "h"), ("q", "k"), ("yo", "o"), ("ya", "a"), ("yu", "u"), ("ts", "s"), ("w", "v")):
        w = w.replace(a, b)
    return re.sub(r"(.)\1+", r"\1", w)


STOP_N = {norm(s) for s in STOP}


def words(s: str) -> list[str]:
    out = []
    for w in re.findall(r"[a-z']{3,}", latin(s)):
        w = norm(w)
        if len(w) >= 3 and w not in STOP_N:
            out.append(w)
    return out


def resolve_period(q: str) -> tuple[dt.date, dt.date, str]:
    t, ql = today(), latin(q)
    dates = []
    for m in re.finditer(r"\b(\d{1,2})[./](\d{1,2})(?:[./](\d{4}|\d{2}(?![./]\d)))?\b", q):
        try:
            y = int(m.group(3)) if m.group(3) else t.year
            dates.append(dt.date(y + 2000 if y < 100 else y, int(m.group(2)), int(m.group(1))))
        except ValueError:
            pass
    if dates:
        a, b = min(dates), max(dates)
        return a, min(b, t), "savoldagi sana"
    if re.search(r"o'tgan hafta|otgan hafta|proshl\w* nedel|predyd\w* nedel", ql):
        a = week_start(t) - dt.timedelta(days=7)
        return a, a + dt.timedelta(days=6), "o'tgan hafta"
    if re.search(r"o'tgan oy|otgan oy|proshl\w* mesyats", ql):
        last = t.replace(day=1) - dt.timedelta(days=1)
        return last.replace(day=1), last, "o'tgan oy"
    if re.search(r"\boy\b|oyi\b|oyda|mesyats|mesyaz", ql):
        return t.replace(day=1), t, "shu oy"
    if re.search(r"hafta|nedel", ql):
        return week_start(t), t, "shu hafta"
    if re.search(r"kecha\b|kechagi|vchera", ql):
        y = t - dt.timedelta(days=1)
        return y, y, "kecha"
    return t, t, "bugun"


def table(rows: list[dict], cols: list[tuple[str, str]]) -> str:
    out = [";".join(h for _, h in cols)]
    for r in rows:
        out.append(";".join("" if r.get(k) is None else str(r.get(k)).replace(";", ",") for k, _ in cols))
    return "\n".join(out)


def build_context(api: Api, q: str) -> tuple[str, dt.date, dt.date]:
    a, b, how = resolve_period(q)
    t = today()
    ctx = [f"Hozir: {dt.datetime.now(TASHKENT):%Y-%m-%d %H:%M} (Toshkent). Ish kuni du–sha, 09:00–18:00.",
           f"Savol davri (avtomatik aniqlandi — {how}): {a} – {b}."]
    days = {b, t, t - dt.timedelta(days=1)} if a == b else {t}
    for d in sorted(days):
        ctx.append(f"\n## Kun ko'rsatkichlari {d}\n" + json.dumps(api.overview(d), ensure_ascii=False))
    agents = api.agents(a, b)
    hours = {r["agentId"]: r for r in api.work_hours(a, b)}
    for r in agents:
        h = hours.get(r["agentId"]) or {}
        r["first"], r["last"] = h.get("avgFirstActivity"), h.get("avgLastActivity")
    ctx.append(f"\n## Agentlar {a} – {b} (planned=reja vizit, closed=bajarilgan, completionRate=%, proofsCompleteRate=dalillar to'liq %, "
               "suspect=shubhali, photosTotal=AI tekshirgan rasm, photosOkRate=polka standarti %, salesUzs=buyurtma so'm, "
               "avgActiveMinutes=o'rtacha faol daqiqa, first/last=o'rtacha birinchi/oxirgi faollik, lateStarts/earlyFinishes=kech/erta kunlar soni)\n"
               + table(agents, [("name", "agent"), ("region", "hudud"), ("daysWorked", "kun"), ("planned", "reja"), ("closed", "bajarildi"),
                                ("completionRate", "bajarish%"), ("proofsCompleteRate", "dalil%"), ("suspect", "shubhali"),
                                ("photosTotal", "AIrasm"), ("photosOkRate", "standart%"), ("conversations", "suhbat"), ("avgScore", "ball"),
                                ("salesUzs", "buyurtma_som"), ("avgActiveMinutes", "faol_daq"), ("first", "birinchi"), ("last", "oxirgi"),
                                ("lateStarts", "kech"), ("earlyFinishes", "erta")]))
    ctx.append(f"\n## Hududlar {a} – {b}\n" + table(api.regions(a, b), [
        ("region", "hudud"), ("agents", "agent"), ("agentsActive", "faol"), ("planned", "reja"), ("visits", "vizit"),
        ("inPlanVisits", "rejadagi"), ("coveragePct", "qamrov%"), ("outPlanVisits", "rejadan_tashqari"), ("farVisits", "uzoqdan"),
        ("withOrder", "buyurtmali"), ("orderRatePct", "buyurtma%"), ("withPhoto", "rasmli"), ("photosSuspect", "eski_rasm")]))
    if a == b:
        try:
            s = photo_stats(api, a)
            ctx.append(f"\n## Polka rasmlari AI tekshiruvi {a}\n" + json.dumps({
                "rasmli_vizitlar": s["withPhoto"], "standart_ok": s["ok"], "standartdan_past_jami": s["bad"],
                "kam": s["by"].get("low", 0), "biolife_yoq": s["by"].get("none", 0), "sanab_bolmadi": s["by"].get("unclear", 0),
                "polka_emas": s["by"].get("not_shelf", 0), "biolife_yoq_agentlar": s["noneAgents"][:40]}, ensure_ascii=False))
        except requests.RequestException as ex:
            log.warning("polka statistikasi olinmadi: %s", ex)
    # Savolda nomi tilga olingan agentlar — kunma-kun ko'rsatkichlar va AI izohlari
    qw = set(words(q))
    if qw:
        freq = collections.Counter(w for r in agents for w in set(words(r.get("name") or "")))
        scored = []
        for r in agents:
            # «Suhrobning», «Сухроба» ham «Suhrob» ga tushadi: savol so'zi ism bilan boshlansa yetadi
            hit = [w for w in set(words(r.get("name") or "")) if len(w) >= 4 and freq[w] <= 4 and any(x.startswith(w) for x in qw)]
            if hit:
                scored.append((len(hit), r))
        scored.sort(key=lambda x: -x[0])
        for _, r in scored[:3]:
            try:
                ser = api.series(r["agentId"], a, b)
                ctx.append(f"\n## Agent {r['name']} — kunma-kun\n" + json.dumps(ser.get("days"), ensure_ascii=False))
                vs = api.visits(b, "photobad", size=8, agent_id=r["agentId"]).get("items") or []
                notes = [{"vaqt": hm_local(v.get("startedAt")), "dokon": v.get("outletName"), "hukm": (v.get("photoAi") or {}).get("verdict"),
                          "izoh": (v.get("photoAi") or {}).get("note")} for v in vs]
                if notes:
                    ctx.append(f"Agent {r['name']} — {b} dagi standartdan past polka rasmlari (AI izohi): " + json.dumps(notes, ensure_ascii=False))
            except requests.RequestException as ex:
                log.warning("agent tafsiloti olinmadi: %s", ex)
    try:
        ctx.append("\n## Tizim manbalari holati\n" + json.dumps(
            [{"manba": s.get("title"), "ok": s.get("ok"), "daqiqa_oldin": s.get("ageMinutes"), "izoh": s.get("detail")} for s in api.sources()],
            ensure_ascii=False))
    except requests.RequestException:
        pass
    return "\n".join(ctx), a, b


class GatewayError(Exception):
    pass


LIMIT_RE = re.compile(r"usage limit|limit reached|hit your limit|rate.?limit|quota|too many|overloaded|resets", re.I)
AUTH_RE = re.compile(r"authenticat|oauth|/login|log ?in|session expired|credential", re.I)


def claude(prompt: str) -> str:
    """Avval `claude` CLI (rasm ishchisi bilan bir xil login), ishlamasa shlyuz. Ikkalasi ham ishlamasa — tushunarli xato."""
    try:
        return claude_cli(prompt)
    except GatewayError as ex:
        if not GATEWAY_TOKEN:
            raise
        log.warning("CLI ishlamadi (%s) — shlyuz orqali", ex)
        try:
            return claude_gateway(prompt)
        except GatewayError:
            raise ex


def claude_cli(prompt: str) -> str:
    argv = [CLI, "-p", "--output-format", "json", "--system-prompt", SYSTEM, "--tools", "", "--safe-mode",
            "--model", CLAUDE_MODEL, "--max-budget-usd", "0.5", "--no-session-persistence"]
    with tempfile.TemporaryDirectory() as cwd:
        try:
            p = subprocess.run(argv, input=prompt.encode(), capture_output=True, timeout=180, cwd=cwd)
        except FileNotFoundError:
            raise GatewayError(f"Claude CLI topilmadi: {CLI}")
        except subprocess.TimeoutExpired:
            raise GatewayError("AI 3 daqiqada javob bermadi — savolni qisqaroq yozib ko'ring.")
    out = p.stdout.decode(errors="replace").strip()
    try:
        d = json.loads(out[out.find("{"):]) if "{" in out else {}
    except ValueError:
        d = {}
    if d.get("type") == "result" and d.get("subtype") == "success" and not d.get("is_error"):
        return str(d.get("result") or "").strip()
    err = str(d.get("result") or d.get("errors") or p.stderr.decode(errors="replace") or f"CLI {p.returncode}")[:300]
    if LIMIT_RE.search(err):
        raise GatewayError("Claude obuna limiti vaqtincha to'lgan — birozdan keyin qayta so'rang. Tez hisobot tugmalari ishlayveradi.")
    if AUTH_RE.search(err):
        raise GatewayError("Serverdagi Claude obunasiga qayta kirish kerak — administratorga ayting. Tez hisobot tugmalari ishlayveradi.")
    raise GatewayError(f"AI javob bermadi: {err[:150]}")


def claude_gateway(prompt: str) -> str:
    body = {"system": SYSTEM, "prompt": prompt, "timeoutMs": 150_000, "maxBudgetUsd": 0.5}
    try:
        r = requests.post(f"{GATEWAY_URL}/v1/complete", headers={"Authorization": f"Bearer {GATEWAY_TOKEN}"}, json=body, timeout=180)
    except requests.RequestException as ex:
        raise GatewayError(f"AI shlyuziga ulanib bo'lmadi: {ex}")
    if r.status_code == 200:
        return str(r.json().get("text") or "").strip()
    try:
        err = str(r.json().get("error") or r.text)[:300]
    except ValueError:
        err = r.text[:300]
    if LIMIT_RE.search(err):
        raise GatewayError("Claude obuna limiti vaqtincha to'lgan — birozdan keyin qayta so'rang. Tez hisobot tugmalari ishlayveradi.")
    if AUTH_RE.search(err):
        raise GatewayError("Serverdagi Claude obunasiga qayta kirish kerak — administratorga ayting. Tez hisobot tugmalari ishlayveradi.")
    raise GatewayError(f"AI javob bermadi ({r.status_code}): {err[:150]}")


ALLOWED_TAGS = re.compile(r"</?(b|i|u|s|code|pre)>")


def clean_html(text: str) -> str:
    """Model javobini Telegram HTML'iga moslash: markdown qalinni <b> ga, ruxsatsiz teglarni matnga aylantirish."""
    text = re.sub(r"```(?:\w+)?\n?(.*?)```", r"<pre>\1</pre>", text, flags=re.S)
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"^#{1,4}\s*(.+)$", r"<b>\1</b>", text, flags=re.M)
    parts, pos = [], 0
    for m in ALLOWED_TAGS.finditer(text):
        parts.append(html.escape(html.unescape(text[pos:m.start()]), quote=False))
        parts.append(m.group(0))
        pos = m.end()
    parts.append(html.escape(html.unescape(text[pos:]), quote=False))
    return "".join(parts).strip()


class Memory:
    """Har chat uchun oxirgi 4 savol-javob (keyingi savol «uniki-chi?» bo'lsa tushunish uchun)."""
    def __init__(self) -> None:
        self.d: dict[str, collections.deque] = {}
        self.lock = threading.Lock()

    def get(self, chat: str) -> list[tuple[float, str, str]]:
        with self.lock:
            return [x for x in self.d.get(chat, []) if time.time() - x[0] < 3 * 3600]

    def add(self, chat: str, q: str, a: str) -> None:
        with self.lock:
            self.d.setdefault(chat, collections.deque(maxlen=4)).append((time.time(), q, a))


MEMORY = Memory()


def answer(api: Api, chat: str, q: str) -> str:
    context, a, b = build_context(api, q)
    hist = MEMORY.get(chat)
    prev = "".join(f"\nRahbar: {x[1]}\nSen: {re.sub(r'<[^>]+>', '', x[2])[:600]}" for x in hist)
    prompt = (f"# Tizim ma'lumoti\n{context}\n\n" + (f"# Oldingi suhbat (eng eskisidan){prev}\n\n" if prev else "")
              + f"# Rahbarning savoli\n{q}")
    log.info("savol: %s | davr %s–%s | %d belgi", q[:120], a, b, len(prompt))
    text = clean_html(claude(prompt))
    if not text:
        raise GatewayError("AI bo'sh javob qaytardi — savolni boshqacha yozib ko'ring.")
    MEMORY.add(chat, q, text)
    return text


# ------------------------------------------------------------------ Telegram

class Tg:
    def __init__(self, token: str) -> None:
        self.base = f"{TG}/bot{token}"
        self.s = requests.Session()

    def call(self, method: str, http_timeout: int = 30, files=None, **params):
        params = {k: v for k, v in params.items() if v is not None}
        if files:
            r = self.s.post(f"{self.base}/{method}", data=params, files=files, timeout=http_timeout)
        else:
            r = self.s.post(f"{self.base}/{method}", json=params, timeout=http_timeout)
        try:
            d = r.json()
        except ValueError:
            raise RuntimeError(f"Telegram {method}: {r.status_code} {r.text[:200]}")
        if not d.get("ok"):
            raise TgError(d.get("error_code") or r.status_code, str(d.get("description")))
        return d.get("result")

    def send(self, chat, text: str, keyboard: dict | None = None, reply_to: int | None = None, inline: dict | None = None) -> int | None:
        msg_id = None
        parts = chunks(text)
        for i, part in enumerate(parts):
            params = {"chat_id": chat, "text": part, "parse_mode": "HTML", "disable_web_page_preview": True}
            last = i == len(parts) - 1
            if last and (inline or keyboard):
                params["reply_markup"] = inline or keyboard
            if i == 0 and reply_to:
                params["reply_parameters"] = {"message_id": reply_to, "allow_sending_without_reply": True}
            try:
                msg_id = self.call("sendMessage", **params)["message_id"]
            except TgError as ex:
                if ex.code != 400 or "parse" not in ex.desc.lower() and "entit" not in ex.desc.lower():
                    raise
                params["text"] = re.sub(r"<[^>]+>", "", html.unescape(part))
                params.pop("parse_mode")
                msg_id = self.call("sendMessage", **params)["message_id"]
        return msg_id


class TgError(Exception):
    def __init__(self, code: int, desc: str):
        super().__init__(f"{code}: {desc}")
        self.code, self.desc = code, desc


def chunks(text: str) -> list[str]:
    out, cur = [], ""
    for line in text.split("\n"):
        if len(cur) + len(line) + 1 > LIMIT and cur:
            out.append(cur)
            cur = ""
        cur = (cur + "\n" if cur else "") + line[:LIMIT]
    if cur:
        out.append(cur)
    return out or ["—"]


def cached_photo(url: str) -> str | None:
    h = hashlib.sha1(url.encode()).hexdigest()
    p = os.path.join(CACHE_DIR, h[:2], h + ".jpg")
    return p if os.path.exists(p) else None


def send_examples(tg: Tg, chat, examples: list[dict]) -> None:
    labels = {"none": "🔴 BioLife yo'q", "low": "🟡 Standartdan kam"}
    for v in examples:
        ai = v.get("photoAi") or {}
        cap = (f"{labels.get(ai.get('verdict'), '')} · <b>{e(v.get('agentName'))}</b> ({e(v.get('region'))})\n"
               f"🏪 {e(v.get('outletName'))} · <code>{hm_local(v.get('startedAt'))}</code>\n"
               f"🤖 {e(ai.get('note') or '')}")[:1000]
        url = v["photoUrls"][0]
        path = cached_photo(url)
        try:
            if path:
                with open(path, "rb") as f:
                    tg.call("sendPhoto", http_timeout=60, files={"photo": ("polka.jpg", f, "image/jpeg")}, chat_id=chat, caption=cap, parse_mode="HTML")
            else:
                tg.call("sendPhoto", chat_id=chat, photo=url, caption=cap, parse_mode="HTML")
        except (TgError, RuntimeError, OSError, requests.RequestException) as ex:
            log.info("namuna rasm yuborilmadi: %s", ex)


WELCOME = ("👋 <b>Assalomu alaykum!</b> Men BioLife savdo bo'limining AI yordamchisiman.\n\n"
           "Savolingizni oddiy so'z bilan yozing, masalan:\n"
           "• <i>bugun kim kech keldi?</i>\n• <i>Samarqandda polka rasmlari qanday?</i>\n"
           "• <i>shu hafta rejani eng kam bajargan agentlar</i>\n• <i>Suhrobning kechagi vizitlari</i>\n\n"
           "Yoki pastdagi tugmani bosing — hisobot darrov keladi.\n"
           "Kunlik hisobot har ish kuni <code>18:30</code> da, haftalik — dushanba <code>09:00</code> da keladi.")


class Bot:
    def __init__(self, tg: Tg, api: Api) -> None:
        self.tg, self.api = tg, api
        self.me = tg.call("getMe")
        self.username = (self.me.get("username") or "").lower()
        self.pool = concurrent.futures.ThreadPoolExecutor(3)
        self.denied: dict[str, float] = {}

    def allowed(self, msg: dict) -> bool:
        """Shaxsiy chat — ruxsatli foydalanuvchi; guruh — faqat hisobot chati (begona guruhga ma'lumot chiqmasin)."""
        user = str((msg.get("from") or {}).get("id", ""))
        chat = str((msg.get("chat") or {}).get("id", ""))
        if (msg.get("chat") or {}).get("type") == "private":
            return user in ALLOWED_USERS or chat in REPORT_CHATS
        return chat in REPORT_CHATS

    def addressed(self, msg: dict, text: str) -> bool:
        if (msg.get("chat") or {}).get("type") == "private":
            return True
        if text.startswith("/") or (self.username and f"@{self.username}" in text.lower()):
            return True
        reply = msg.get("reply_to_message") or {}
        return (reply.get("from") or {}).get("id") == self.me.get("id") or text in BUTTONS

    def handle(self, upd: dict) -> None:
        msg = upd.get("message")
        if not msg or not msg.get("text"):
            return
        text = msg["text"].strip()
        chat = msg["chat"]["id"]
        if not self.addressed(msg, text):
            return
        if not self.allowed(msg):
            uid = str((msg.get("from") or {}).get("id", ""))
            log.info("ruxsatsiz: user %s chat %s (%s)", uid, chat, (msg.get("from") or {}).get("username"))
            if time.time() - self.denied.get(f"{uid}:{chat}", 0) > 3600:
                self.denied[f"{uid}:{chat}"] = time.time()
                group = (msg.get("chat") or {}).get("type") != "private"
                self.tg.send(chat, "⛔ Bu bot faqat BioLife savdo rahbariyati uchun.\n"
                                   f"Sizning Telegram ID raqamingiz: <code>{e(uid)}</code>\n"
                                   + (f"Bu guruh ID raqami: <code>{chat}</code>\n" if group else "")
                                   + "Ruxsat olish uchun shu raqamni administratorga yuboring.")
            return
        if self.username:
            text = re.sub(rf"@{re.escape(self.username)}\b", "", text, flags=re.I).strip()
        self.pool.submit(self.reply, chat, msg.get("message_id"), text)

    def reply(self, chat, msg_id: int, text: str) -> None:
        try:
            cmd = text.split()[0].lower().split("@")[0] if text.startswith("/") else ""
            if cmd in ("/start", "/menu", "/help", "/yordam"):
                self.tg.send(chat, WELCOME, keyboard=KEYBOARD)
            elif cmd == "/id":
                self.tg.send(chat, f"Chat ID: <code>{chat}</code>")
            elif text in BUTTONS:
                BUTTONS[text](self, chat)
            else:
                self.ask(chat, msg_id, text)
        except Exception as ex:
            log.exception("javobda xato")
            try:
                self.tg.send(chat, f"⚠️ Hozir javob bera olmadim: {e(str(ex)[:200])}\nBirozdan keyin qayta urinib ko'ring.")
            except Exception:
                pass

    def ask(self, chat, msg_id: int, text: str) -> None:
        if len(text) < 2:
            return
        done = threading.Event()

        def typing():
            while not done.is_set():
                try:
                    self.tg.call("sendChatAction", chat_id=chat, action="typing")
                except Exception:
                    pass
                done.wait(5)
        threading.Thread(target=typing, daemon=True).start()
        try:
            out = answer(self.api, str(chat), text)
        except GatewayError as ex:
            out = f"⚠️ {e(ex)}"
        finally:
            done.set()
        self.tg.send(chat, out, reply_to=msg_id)

    def show_day(self, chat, d: dt.date) -> None:
        self.tg.send(chat, day_report(self.api, d), keyboard=KEYBOARD)

    def show_photo(self, chat) -> None:
        d = today()
        txt, ex = photo_report(self.api, d)
        if not ex and "hali rasm tekshirmagan" in txt:
            d = d - dt.timedelta(days=1)
            txt, ex = photo_report(self.api, d)
        self.tg.send(chat, txt, keyboard=KEYBOARD)
        send_examples(self.tg, chat, ex)

    def show_panel(self, chat) -> None:
        url = public_url()
        if url:
            self.tg.send(chat, "🌐 Rahbar paneli — to'liq jadval, rasmlar va suhbatlar:",
                         inline={"inline_keyboard": [[{"text": "🌐 Panelni ochish", "url": url + "/panel"}]]})
        else:
            self.tg.send(chat, "🌐 Panel manzili hozir aniqlanmadi — birozdan keyin qayta bosing.")

    def run(self) -> None:
        log.info("bot ishga tushdi: @%s, ruxsat: %d foydalanuvchi, %d chat", self.username, len(ALLOWED_USERS), len(REPORT_CHATS))
        try:
            self.tg.call("setMyCommands", commands=[{"command": "start", "description": "Menyu va yordam"},
                                                    {"command": "id", "description": "Chat ID raqami"}])
        except Exception as ex:
            log.warning("buyruqlar ro'yxati o'rnatilmadi: %s", ex)
        offset = None
        while True:
            try:
                ups = self.tg.call("getUpdates", http_timeout=70, offset=offset, timeout=50, allowed_updates=["message"])
            except TgError as ex:
                if ex.code == 409:
                    log.error("bu token boshqa joyda ham ishlatilmoqda (webhook yoki boshqa bot jarayoni): %s", ex.desc)
                    time.sleep(120)
                elif ex.code == 401:
                    log.error("bot tokeni noto'g'ri yoki bekor qilingan")
                    time.sleep(1800)
                else:
                    log.warning("getUpdates: %s", ex)
                    time.sleep(10)
                continue
            except (requests.RequestException, RuntimeError) as ex:
                log.warning("Telegram'ga ulanib bo'lmadi: %s", ex)
                time.sleep(10)
                continue
            for u in ups or []:
                offset = u["update_id"] + 1
                try:
                    self.handle(u)
                except Exception:
                    log.exception("xabarni ko'rishda xato")


BUTTONS = {
    B_TODAY: lambda b, c: b.show_day(c, today()),
    B_YESTERDAY: lambda b, c: b.show_day(c, today() - dt.timedelta(days=1 if today().weekday() != 0 else 2)),
    B_PHOTO: lambda b, c: b.show_photo(c),
    B_AGENTS: lambda b, c: b.tg.send(c, agents_report(b.api), keyboard=KEYBOARD),
    B_HOURS: lambda b, c: b.tg.send(c, hours_report(b.api, today()), keyboard=KEYBOARD),
    B_REGIONS: lambda b, c: b.tg.send(c, regions_report(b.api, today()), keyboard=KEYBOARD),
    B_PANEL: lambda b, c: b.show_panel(c),
}


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    if not USER or not PASS:
        log.error("AI_WORKER_USER / AI_WORKER_PASS sozlanmagan")
        return 2
    if not TOKEN:
        log.warning("bot tokeni yo'q (BOT_TOKEN yoki BIOLIFE_REPORT_BOT_TOKEN) — 10 daqiqadan keyin .env qayta o'qiladi")
        time.sleep(600)
        return 0
    if not ALLOWED_USERS and not REPORT_CHATS:
        log.warning("BOT_ALLOWED_IDS va BIOLIFE_REPORT_CHAT_ID bo'sh — bot hammaga faqat ID raqamini aytadi")
    while True:
        try:
            Bot(Tg(TOKEN), Api()).run()
        except TgError as ex:
            log.error("Telegram: %s", ex)
            time.sleep(1800 if ex.code == 401 else 60)
        except (requests.RequestException, RuntimeError) as ex:
            log.warning("ishga tushmadi: %s", ex)
            time.sleep(30)


def cli(argv: list[str]) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stderr)
    api = Api()
    if argv[0] == "--ask":
        t0 = time.time()
        try:
            print(answer(api, "cli", " ".join(argv[1:])))
        except GatewayError as ex:
            print("XATO:", ex)
        print(f"\n[{time.time() - t0:.0f} s]", file=sys.stderr)
        return 0
    what = argv[1] if len(argv) > 1 else "bugun"
    shows = {"bugun": lambda: day_report(api, today()), "kecha": lambda: day_report(api, today() - dt.timedelta(days=1)),
             "polka": lambda: (lambda r: r[0] + f"\n[namuna: {len(r[1])}]")(photo_report(api, today())),
             "agentlar": lambda: agents_report(api), "vaqt": lambda: hours_report(api, today()),
             "hududlar": lambda: regions_report(api, today())}
    print(shows[what]())
    return 0


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] in ("--ask", "--show"):
        sys.exit(cli(sys.argv[1:]))
    sys.exit(main())
