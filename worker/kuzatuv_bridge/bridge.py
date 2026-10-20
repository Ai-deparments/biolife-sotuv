#!/usr/bin/env python3
"""
Kuzatuv → BioLife Agent AI ko'prigi.

Biolife Kuzatuv (serverdagi SQLite; Linko va RITM'dan o'zi to'ldiriladi) FAQAT O'QILADI.
Undagi haqiqiy faktlar biolife-api'ning `POST /api/v1/internal/events/{type}` yo'liga voqea
bo'lib yuboriladi (shared.events.DomainEvents shartnomasi):

  agent (faol dala agenti)           → AgentProfileChanged  region = RITM filiali
  route_plan (hafta kuni)            → DayPlanned           shu kungi rejadagi do'konlar soni
  linko_visit (Linko vizit hisoboti) → VisitStarted         withinRadius: masofa ≤ Kuzatuv'dagi «do'kondan uzoq» chegarasi
                                     → VisitClosed          vizit tugagach (+ muhlat); dalillar to'liq = joyida + rasm + buyurtma
  photo_status (kuzatuvchi belgisi)  → PhotoChecked         «polka to'la» = ok, «qisman» = low; belgisiz rasm yuborilmaydi
  activity_event kind='order'        → OrderConfirmed       summa = total_price; to'liq qaytarilgan buyurtma hisoblanmaydi
                                                             (Kuzatuv qoidasi bilan bir xil, status qiymatlariga tegilmaydi)
  vizit + do'kon + rasm + buyurtma   → VisitRecorded        rahbar panelidagi vizitlar ro'yxati uchun: do'kon nomi, vaqt,
                                                             masofa, rejadami, rasmlar (havolasi bilan), shu do'kondagi buyurtmalar;
                                                             biror narsa o'zgarsa qayta yuboriladi
  Kuzatuv sync holati                → SourceSynced         ko'prik, Linko vizitlari, RITM buyurtma va rasmlari qachon yangilangani

Savdo summasi Kuzatuv bilan so'mma-so'm mos kelishi uchun:
  * summalar tiyingacha aniq (Decimal) qo'shiladi; API butun so'm qabul qiladi, shuning uchun har kun
    bo'yicha yig'ma yaxlitlanadi — kunlik jami doim round(aniq jami), Kuzatuv ko'rsatgani bilan bir xil;
  * yuborilgan buyurtma keyin o'zgarsa (summasi tahrirlansa yoki to'liq qaytarilsa — odatda 0–9 kun ichida),
    farq tuzatish yozuvi bo'lib yuboriladi (manfiy bo'lishi mumkin). Kuzatuv'dan yo'qolgan qator e'tiborsiz
    qoladi: Kuzatuv eski ma'lumotni o'zi tozalaydi, bu buyurtma bekor bo'lgani degani emas.

Buyurtma vizitga shu agent + shu do'kon + shu kun bo'yicha bog'lanadi. Do'kon vizitisiz buyurtma va
tuzatishlar agentning o'sha kungi buyurtmasiz yopilgan vizitiga yoziladi (savdo kun va agent bo'yicha
to'g'ri tushadi, vizitlar soni ko'paymaydi); bunday vizit bo'lmasa — alohida yozuv.

Faqat Linko vizit hisobotidagi vizitlar olinadi: Kuzatuv'ning taxminiy («derived») vizitlari keyin
almashtirilishi mumkin va ularda masofa yo'q. O'tgan kunlar rejasi bir marta yoziladi: Kuzatuv faqat
joriy marshrut rejasini saqlaydi, shuning uchun birinchi yuklashda o'tgan kunlarga ham joriy reja qo'yiladi.

Har voqea bir marta yuboriladi (holat: state.sqlite). Identifikatorlar barqaror UUIDv5: agent —
"kuzatuv-agent:<source_id>:<source_ref>" (agent login'i keyin shu id bilan yaratiladi), vizit — Linko
hisobotidagi kalit (sana|agent|do'kon|kelish soniyasi), alohida buyurtma — "kuzatuv-order:<RITM id>".

Sozlamalar (muhit, odatda ~/biolife-sales/.env):
  KUZATUV_DB, KUZATUV_ENV    Kuzatuv bazasi va .env (dala agenti qoidalari BOT_*_PATTERN shu yerdan)
  BRIDGE_API, BRIDGE_USER, BRIDGE_PASS   biolife-api va ADMIN servis hisobi
  BRIDGE_SINCE               eng eski sana (YYYY-MM-DD)
  BRIDGE_LOOKBACK_DAYS       har o'tishda qayta ko'riladigan kunlar (kech kelgan buyurtma, tuzatish, rasm belgisi; 10)
  BRIDGE_RADIUS_M            «joyida» chegarasi, metr; bo'sh bo'lsa Kuzatuv sozlamasi watch.farMeters (300)
  BRIDGE_GRACE_MIN           vizit tugagach kutish, keyin yopiladi (30)
  BRIDGE_LOOSE_AFTER_MIN     do'kon vizitisiz buyurtma shuncha kutadi (120)
  BRIDGE_EVERY_SEC           takrorlash oralig'i; 0 — bir marta (300)
  BRIDGE_DRY_RUN=1           hech narsa yubormaydi, faqat sanaydi (tekshirish uchun, alohida BRIDGE_STATE bilan)

Bir martalik buyruq:
  bridge.py --replay-masters   avval yuborilgan agentlar va kunlik rejani AYNAN o'sha qiymatlar bilan qayta yuboradi.
                               API'ga ularni o'qiydigan yangi modul qo'shilganda kerak; boshqa modullarda hech narsa o'zgarmaydi.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import logging
import os
import re
import signal
import sqlite3
import sys
import time
import uuid
from collections import Counter, defaultdict
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation

import requests

log = logging.getLogger("bridge")
TZ = dt.timezone(dt.timedelta(hours=5))  # Asia/Tashkent, yozgi vaqt yo'q
NS = uuid.UUID("3b1f6a2e-7c4d-5e8f-9a0b-1c2d3e4f5a6b")
ZERO = Decimal(0)


def env(name: str, default: str = "") -> str:
    value = os.environ.get(name, "")
    return value if value.strip() else default


KUZATUV_DB = env("KUZATUV_DB", "/home/ubuntu/kuzatuv/data/kuzatuv.sqlite")
KUZATUV_ENV = env("KUZATUV_ENV", "/home/ubuntu/kuzatuv/.env")
STATE_DB = env("BRIDGE_STATE", os.path.join(os.path.dirname(os.path.abspath(__file__)), "state.sqlite"))
API = env("BRIDGE_API", "http://127.0.0.1:8130").rstrip("/")
USER = env("BRIDGE_USER")
PASS = env("BRIDGE_PASS")
SINCE = dt.date.fromisoformat(env("BRIDGE_SINCE", (dt.datetime.now(TZ).date() - dt.timedelta(days=14)).isoformat()))
LOOKBACK = dt.timedelta(days=int(env("BRIDGE_LOOKBACK_DAYS", "10")))
RADIUS_OVERRIDE = env("BRIDGE_RADIUS_M")
GRACE = dt.timedelta(minutes=int(env("BRIDGE_GRACE_MIN", "30")))
LOOSE_AFTER = dt.timedelta(minutes=int(env("BRIDGE_LOOSE_AFTER_MIN", "120")))
EVERY = int(env("BRIDGE_EVERY_SEC", "300"))
DRY = env("BRIDGE_DRY_RUN") == "1"
ORDER_BEFORE = dt.timedelta(minutes=15)   # buyurtma vizit boshlanishidan shuncha oldin ham shu vizitniki
ORDER_AFTER = dt.timedelta(minutes=60)    # va tugaganidan shuncha keyin ham

# Kuzatuv'dagi rol qoidalari (server/src/domain/workforce.mjs bilan bir xil): faqat dala savdo agentlari olinadi.
SUPERVISOR_RE = re.compile(r"супервайз|супервиз|supervayz|supervaiz|supervis|superviz", re.I)
NON_FIELD_RE = re.compile(
    r"кассир|kassir|продав|prodav|бухгалт|buxgalt|bukhgalt|buhgalt|оператор|operator|оптом|optom|админ|admin"
    r"|ритм|ritm|офис|ofis|office|(?<![^\W_])(?:бозор|bozor|базар|bazar)(?![^\W\d_])", re.I)
MERCH_RE = re.compile(r"мерч|merch", re.I)


def kuzatuv_patterns() -> tuple[re.Pattern | None, re.Pattern | None]:
    """BOT_NON_FIELD_PATTERN va BOT_MERCH_PATTERN — Kuzatuv .env'idan (faqat o'qiladi)."""
    found: dict[str, str] = {}
    try:
        with open(KUZATUV_ENV, encoding="utf-8") as f:
            for line in f:
                m = re.match(r"^\s*(BOT_NON_FIELD_PATTERN|BOT_MERCH_PATTERN)\s*=\s*(.*?)\s*$", line)
                if m:
                    found[m.group(1)] = m.group(2).strip().strip('"').strip("'")
    except OSError:
        pass

    def compile_or_none(text: str) -> re.Pattern | None:
        if not text:
            return None
        try:
            return re.compile(text, re.I)
        except re.error:
            log.warning("Kuzatuv shabloni o'qilmadi, e'tiborsiz qoldirildi: %s", text)
            return None

    return compile_or_none(found.get("BOT_NON_FIELD_PATTERN", "")), compile_or_none(found.get("BOT_MERCH_PATTERN", ""))


def is_field_agent(name: str, manual: int | None, extra_non_field: re.Pattern | None, extra_merch: re.Pattern | None) -> bool:
    if manual == 0:
        return False
    if manual == 1:
        return True
    if SUPERVISOR_RE.search(name) or NON_FIELD_RE.search(name) or MERCH_RE.search(name):
        return False
    if (extra_non_field and extra_non_field.search(name)) or (extra_merch and extra_merch.search(name)):
        return False
    return True


def u5(kind: str, key: str) -> str:
    return str(uuid.uuid5(NS, f"{kind}:{key}"))


def parse_ts(text: str) -> dt.datetime:
    return dt.datetime.fromisoformat(text.replace("Z", "+00:00"))


def local_date(text: str) -> str:
    return parse_ts(text).astimezone(TZ).date().isoformat()


def iso(moment: dt.datetime) -> str:
    return moment.astimezone(dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def to_amount(value) -> Decimal | None:
    """Summa satr bo'lib keladi («29646.600000000»); nosonli summa qo'shilmaydi (Kuzatuv ham shunday)."""
    if value is None:
        return None
    try:
        x = Decimal(str(value).replace(" ", "").replace(",", "."))
    except (InvalidOperation, ValueError):
        return None
    return x if x.is_finite() else None


def som(x: Decimal) -> int:
    """Butun so'mga yaxlitlash — Kuzatuv ekranidagi Math.round bilan bir xil (0,5 yuqoriga)."""
    return int(x.quantize(Decimal(1), rounding=ROUND_HALF_UP))


def radius_m(kz: sqlite3.Connection) -> float:
    if RADIUS_OVERRIDE:
        return float(RADIUS_OVERRIDE)
    try:
        row = kz.execute("select value from setting where key = 'watch.farMeters'").fetchone()
        if row:
            value = row[0]
            try:
                value = json.loads(value)
            except (TypeError, ValueError):
                pass
            if isinstance(value, dict):
                value = value.get("value")
            return float(value)
    except (sqlite3.Error, TypeError, ValueError):
        pass
    return 300.0


# ------------------------------------------------------------------ API

class Api:
    def __init__(self) -> None:
        self.session = requests.Session()
        self.token: str | None = None
        self.sent: Counter = Counter()
        self.t0 = time.time()

    def login(self) -> None:
        r = self.session.post(f"{API}/api/v1/auth/login", json={"username": USER, "password": PASS}, timeout=20)
        if r.status_code != 200:
            raise RuntimeError(f"login {r.status_code}: {r.text[:200]}")
        self.token = r.json()["accessToken"]

    def publish(self, typ: str, body: dict) -> None:
        if not DRY:
            if not self.token:
                self.login()
            for attempt in (1, 2):
                r = self.session.post(f"{API}/api/v1/internal/events/{typ}", json=body,
                                      headers={"Authorization": f"Bearer {self.token}"}, timeout=60)
                if r.status_code == 401 and attempt == 1:
                    self.login()
                    continue
                if r.status_code >= 300:
                    raise RuntimeError(f"{typ} → {r.status_code}: {r.text[:300]}")
                break
        self.sent[typ] += 1
        total = sum(self.sent.values())
        if total % 5000 == 0:
            log.info("… %d voqea yuborildi (%.0f/s)", total, total / max(1e-6, time.time() - self.t0))


# ------------------------------------------------------------------ HOLAT

def open_state() -> sqlite3.Connection:
    st = sqlite3.connect(STATE_DB)
    old = {r[1] for r in st.execute("pragma table_info(order_sent)")}
    if old and "amount" not in old:
        raise SystemExit(f"{STATE_DB} eski versiyada (summalarsiz). Qayta yuklash kerak: API faktlari va holat tozalanadi.")
    st.executescript("""
        create table if not exists meta (key text primary key, value text not null);
        create table if not exists agent_sent (kuz_id integer primary key, fp text not null);
        create table if not exists day_planned (agent text not null, date text not null, planned integer not null, primary key (agent, date));
        create table if not exists visit_state (vid text primary key, date text not null, started integer not null default 0,
                                                closed integer not null default 0, photo integer not null default 0, ordered integer not null default 0);
        create index if not exists visit_state_date on visit_state (date);
        -- amount: shu buyurtmadan hozirgacha hisobga olingan aniq summa (tuzatishlar bilan)
        create table if not exists order_sent (ext_id text primary key, vid text not null, date text not null, agent_kid integer not null,
                                               agent_uuid text not null, amount text not null, fixes integer not null default 0);
        create index if not exists order_sent_date on order_sent (date);
        -- kun bo'yicha: aniq jami (Decimal) va API'ga yuborilgan butun so'm jami
        create table if not exists money_day (date text primary key, exact text not null, sent integer not null);
    """)
    # rec_fp: vizitlar ro'yxatiga (VisitRecorded) oxirgi yuborilgan ko'rinishning izi — o'zgarsa qayta yuboriladi
    if "rec_fp" not in {r[1] for r in st.execute("pragma table_info(visit_state)")}:
        st.execute("alter table visit_state add column rec_fp text")
    st.commit()
    return st


def meta_get(st: sqlite3.Connection, key: str) -> str | None:
    row = st.execute("select value from meta where key = ?", (key,)).fetchone()
    return row[0] if row else None


def meta_set(st: sqlite3.Connection, key: str, value: str) -> None:
    st.execute("insert into meta (key, value) values (?, ?) on conflict(key) do update set value = excluded.value", (key, value))


# ------------------------------------------------------------------ BIR O'TISH

def run_once(api: Api, st: sqlite3.Connection) -> Counter:
    kz = sqlite3.connect(f"file:{KUZATUV_DB}?mode=ro", uri=True)
    kz.row_factory = sqlite3.Row
    try:
        return sync(api, st, kz)
    finally:
        kz.close()
        st.commit()


def sync(api: Api, st: sqlite3.Connection, kz: sqlite3.Connection) -> Counter:
    now = dt.datetime.now(dt.timezone.utc)
    today = now.astimezone(TZ).date()
    today_iso = today.isoformat()
    last_ok = meta_get(st, "last_ok_date")
    start = SINCE if last_ok is None else max(SINCE, min(today - LOOKBACK, dt.date.fromisoformat(last_ok) - dt.timedelta(days=1)))
    if meta_get(st, "visit_list_since") is None:
        start = SINCE  # vizitlar ro'yxati qo'shilgandan keyingi birinchi o'tish butun tarixni bir marta yuboradi
    start_iso = start.isoformat()
    start_utc = iso(dt.datetime.combine(start, dt.time(0), TZ))
    radius = radius_m(kz)
    stats: Counter = Counter()
    extra_non_field, extra_merch = kuzatuv_patterns()

    # 1. Agentlar: faol dala agentlari. Kuzatuv agent.id → bizning UUID.
    agents: dict[int, str] = {}
    refs: dict[int, str] = {}  # Kuzatuv agent.id → RITM agent id (route_plan.agent_ref)
    for r in kz.execute("""select a.id, a.source_id, a.source_ref, a.full_name, a.active, b.name as branch, f.is_field
                           from agent a left join branch b on b.id = a.branch_id
                           left join agent_field f on f.agent_id = a.id""").fetchall():
        active = bool(r["active"]) and is_field_agent(r["full_name"], r["is_field"], extra_non_field, extra_merch)
        uid = u5("kuzatuv-agent", f"{r['source_id']}:{r['source_ref']}")
        region = r["branch"] or "Filialsiz"
        if active:
            agents[r["id"]] = uid
            refs[r["id"]] = r["source_ref"]
        fp = f"{r['full_name']}|{region}|{int(active)}"
        prev = st.execute("select fp from agent_sent where kuz_id = ?", (r["id"],)).fetchone()
        if (prev is None and not active) or (prev is not None and prev[0] == fp):
            continue
        api.publish("AgentProfileChanged", {"agentId": uid, "name": r["full_name"], "region": region, "district": region, "active": active})
        st.execute("insert into agent_sent (kuz_id, fp) values (?, ?) on conflict(kuz_id) do update set fp = excluded.fp", (r["id"], fp))
        stats["agents"] += 1
    st.commit()
    if not agents:
        return stats

    # 2. Kunlik reja: route_plan (hafta kuni 1 = dushanba … 7 = yakshanba). Yakshanba ish kuni emas.
    plan = {(r["agent_ref"], r["weekday"]): r["n"] for r in kz.execute("select agent_ref, weekday, count(*) as n from route_plan group by 1, 2")}
    day = start
    while day <= today:
        if day.isoweekday() != 7:
            for kid, agent_uuid in agents.items():
                n = plan.get((refs.get(kid), day.isoweekday()), 0)
                prev = st.execute("select planned from day_planned where agent = ? and date = ?", (agent_uuid, day.isoformat())).fetchone()
                if prev is not None and (day < today or prev[0] == n):
                    continue  # o'tgan kun rejasi o'zgarmaydi
                if prev is None and n == 0:
                    continue
                api.publish("DayPlanned", {"agentId": agent_uuid, "date": day.isoformat(), "plannedVisits": n})
                st.execute("insert into day_planned (agent, date, planned) values (?, ?, ?) "
                           "on conflict(agent, date) do update set planned = excluded.planned", (agent_uuid, day.isoformat(), n))
                stats["plans"] += 1
        day += dt.timedelta(days=1)
    st.commit()

    # 3. Holat (oyna ichida).
    vstate = {r[0]: {"started": r[1], "closed": r[2], "photo": r[3], "ordered": r[4], "rec_fp": r[5]} for r in st.execute(
        "select vid, started, closed, photo, ordered, rec_fp from visit_state where date >= ?", (start_iso,))}
    sent_orders = {r[0] for r in st.execute("select ext_id from order_sent where date >= ?", (start_iso,))}
    money = {r[0]: [Decimal(r[1]), r[2]] for r in st.execute("select date, exact, sent from money_day where date >= ?", (start_iso,))}

    # 4. Kuzatuv'dan o'qish (hammasi oldin o'qiladi, keyin yuboriladi — bazada uzoq o'qish ochiq qolmaydi).
    visits: list[dict] = []
    by_key: dict[tuple, list[dict]] = defaultdict(list)       # (agent, do'kon, kun) → vizitlar
    by_agent_day: dict[tuple, list[dict]] = defaultdict(list)  # (agent, kun) → vizitlar
    for r in kz.execute("""select l.work_date, l.agent_name, l.market_ref, l.start_local, l.agent_id, l.outlet_id, l.started_at, l.ended_at,
                                  l.distance_m, l.in_plan, coalesce(o.name, l.market_name) as outlet_name, o.source_ref as outlet_ref
                           from linko_visit l left join outlet o on o.id = l.outlet_id
                           where l.match_status = 'matched' and l.work_date >= ?
                           order by l.work_date, l.start_local""", (start_iso,)).fetchall():
        if r["agent_id"] not in agents:
            continue
        v = {"vid": u5("linko-visit", f"{r['work_date']}|{r['agent_name']}|{r['market_ref']}|{r['start_local']}"),
             "agent": r["agent_id"], "outlet": r["outlet_id"], "date": r["work_date"],
             "start": parse_ts(r["started_at"]), "end": parse_ts(r["ended_at"]) if r["ended_at"] else None,
             "dist": r["distance_m"], "in_plan": None if r["in_plan"] is None else bool(r["in_plan"]),
             "outlet_name": r["outlet_name"], "outlet_ref": r["outlet_ref"] or r["market_ref"] or None}
        visits.append(v)
        by_key[(v["agent"], v["outlet"], v["date"])].append(v)
        by_agent_day[(v["agent"], v["date"])].append(v)

    photo_info: dict[tuple, dict] = {}   # (agent, do'kon, kun) → {"n", "suspect", "urls"} — yangi rasm birinchi
    for r in kz.execute("""select agent_id, outlet_id, taken_at, time_trust, remote_url from photo
                           where taken_at >= ? and agent_id is not null and outlet_id is not null
                           order by taken_at desc""", (start_utc,)).fetchall():
        info = photo_info.setdefault((r["agent_id"], r["outlet_id"], local_date(r["taken_at"])), {"n": 0, "suspect": 0, "urls": []})
        info["n"] += 1
        if r["time_trust"] == "suspect":
            info["suspect"] += 1
        if r["remote_url"] and r["remote_url"].startswith("https://") and len(info["urls"]) < 6:
            info["urls"].append(r["remote_url"])
    photo_days = set(photo_info)

    labels: dict[tuple, str] = {}
    for r in kz.execute("""select p.agent_id, p.outlet_id, p.taken_at, s.status from photo_status s join photo p on p.id = s.photo_id
                           where s.superseded_by is null and s.confirmed = 1 and s.status in ('shelf_full', 'shelf_partial')
                             and p.taken_at >= ?""", (start_utc,)).fetchall():
        k = (r["agent_id"], r["outlet_id"], local_date(r["taken_at"]))
        labels[k] = "ok" if r["status"] == "shelf_full" or labels.get(k) == "ok" else "low"

    current: dict[str, Decimal] = {}   # RITM id → hozirgi hisobga olinadigan summa (to'liq qaytarilgan = 0)
    new_orders: list[dict] = []
    visit_orders: dict[str, list[Decimal]] = defaultdict(list)   # vizit → shu do'kondagi barcha buyurtma summalari (ro'yxat uchun)
    for r in kz.execute("""select external_id, agent_id, outlet_id, occurred_at, json_extract(payload, '$.total_price') as total,
                                  coalesce(json_extract(payload, '$.is_full_return'), 0) in (1, 'true') as returned
                           from activity_event where kind = 'order' and occurred_at >= ?""", (start_utc,)).fetchall():
        amount = to_amount(r["total"])
        if amount is None:
            continue
        amount = ZERO if r["returned"] else amount
        current[r["external_id"]] = amount
        if amount == ZERO or r["agent_id"] not in agents:
            continue
        t = parse_ts(r["occurred_at"])
        o = {"ext": r["external_id"], "agent": r["agent_id"], "t": t, "date": t.astimezone(TZ).date().isoformat(), "amt": amount, "visit": None}
        cands = by_key.get((r["agent_id"], r["outlet_id"], o["date"]), [])
        for v in cands:
            if v["start"] - ORDER_BEFORE <= t <= (v["end"] or v["start"]) + ORDER_AFTER:
                o["visit"] = v
                break
        if o["visit"] is None and cands:
            o["visit"] = min(cands, key=lambda c: abs((c["start"] - t).total_seconds()))
        if o["visit"] is not None:
            visit_orders[o["visit"]["vid"]].append(amount)
        if r["external_id"] not in sent_orders:
            new_orders.append(o)
    matched: dict[str, list[dict]] = defaultdict(list)
    for o in new_orders:
        if o["visit"] is not None:
            matched[o["visit"]["vid"]].append(o)

    # 5. Yuborish.
    def mark(v: dict, field: str) -> None:
        vstate.setdefault(v["vid"], {"started": 0, "closed": 0, "photo": 0, "ordered": 0, "rec_fp": None})[field] = 1
        st.execute(f"insert into visit_state (vid, date, {field}) values (?, ?, 1) on conflict(vid) do update set {field} = 1", (v["vid"], v["date"]))

    def send_money(vid: str, agent_uuid: str, date: str, exact: Decimal) -> bool:
        """Kun bo'yicha yig'ma yaxlitlash: kunlik jami doim som(aniq jami). Yuborildimi — qaytaradi."""
        day_exact, day_sent = money.get(date, [ZERO, 0])
        new_exact = day_exact + exact
        amount = som(new_exact) - day_sent
        if amount != 0:
            api.publish("OrderConfirmed", {"visitId": vid, "agentId": agent_uuid, "date": date, "amountUzs": amount, "blocks": 0})
        money[date] = [new_exact, day_sent + amount]
        st.execute("insert into money_day (date, exact, sent) values (?, ?, ?) on conflict(date) do update set exact = excluded.exact, sent = excluded.sent",
                   (date, str(new_exact), day_sent + amount))
        return amount != 0

    def record_orders(group: list[dict], vid: str) -> None:
        for o in group:
            sent_orders.add(o["ext"])
            st.execute("insert or ignore into order_sent (ext_id, vid, date, agent_kid, agent_uuid, amount) values (?, ?, ?, ?, ?, ?)",
                       (o["ext"], vid, o["date"], o["agent"], agents[o["agent"]], str(o["amt"])))

    def absorber(agent_kid: int, date: str, near: dt.datetime | None = None) -> dict | None:
        """Agentning shu kungi yopilgan, buyurtmasiz vizitlaridan biri (do'kon vizitisiz buyurtma va tuzatishlar uchun)."""
        cands = [v for v in by_agent_day.get((agent_kid, date), [])
                 if vstate.get(v["vid"], {}).get("closed") and not vstate.get(v["vid"], {}).get("ordered") and not visit_orders.get(v["vid"])]
        if not cands:
            return None
        return min(cands, key=lambda c: abs((c["start"] - near).total_seconds())) if near else cands[-1]

    for i, v in enumerate(visits):
        s = dict(vstate.get(v["vid"], {}))
        agent_uuid = agents[v["agent"]]
        within = v["dist"] is None or float(v["dist"]) <= radius
        if not s.get("started"):
            api.publish("VisitStarted", {"visitId": v["vid"], "agentId": agent_uuid, "startedAt": iso(v["start"]), "withinRadius": within})
            mark(v, "started")
            stats["started"] += 1
        key = (v["agent"], v["outlet"], v["date"])
        if key in labels and not s.get("photo"):
            api.publish("PhotoChecked", {"visitId": v["vid"], "agentId": agent_uuid, "date": v["date"], "verdict": labels[key], "facings": 0, "expected": 0})
            mark(v, "photo")
            stats["photo_labels"] += 1
        end = v["end"] or (v["start"] if v["date"] < today_iso else None)
        settled = end is not None and now - end >= GRACE
        if settled and not s.get("ordered"):
            unsent = [o for o in matched.get(v["vid"], []) if o["ext"] not in sent_orders]
            if unsent:
                if send_money(v["vid"], agent_uuid, v["date"], sum((o["amt"] for o in unsent), ZERO)):
                    mark(v, "ordered")
                record_orders(unsent, v["vid"])
                stats["orders"] += len(unsent)
        if settled and not s.get("closed"):
            api.publish("VisitClosed", {"visitId": v["vid"], "agentId": agent_uuid, "date": v["date"], "closedAt": iso(end),
                                        "proofsComplete": within and key in photo_days and bool(visit_orders.get(v["vid"])),
                                        "excused": False, "suspect": not within, "blocks": 0})
            mark(v, "closed")
            stats["closed"] += 1
        # Rahbar panelidagi vizitlar ro'yxati: ko'rinish o'zgargandagina qayta yuboriladi.
        info = photo_info.get(key, {"n": 0, "suspect": 0, "urls": []})
        amounts = visit_orders.get(v["vid"], [])
        rec = {"visitId": v["vid"], "agentId": agent_uuid, "date": v["date"], "startedAt": iso(v["start"]),
               "endedAt": iso(v["end"]) if v["end"] else None, "outletRef": v["outlet_ref"], "outletName": v["outlet_name"],
               "distanceM": None if v["dist"] is None else int(round(float(v["dist"]))), "withinRadius": within, "inPlan": v["in_plan"],
               "photos": info["n"], "photosSuspect": info["suspect"], "photoUrls": info["urls"],
               "orders": len(amounts), "ordersUzs": som(sum(amounts, ZERO))}
        fp = hashlib.sha1(json.dumps(rec, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()[:16]
        if fp != s.get("rec_fp"):
            rec["recordedAt"] = iso(now)   # versiya: kech kelgan eski yangilanish yangisini bosmaydi (izga kirmaydi)
            api.publish("VisitRecorded", rec)
            vstate.setdefault(v["vid"], {"started": 0, "closed": 0, "photo": 0, "ordered": 0, "rec_fp": None})["rec_fp"] = fp
            st.execute("insert into visit_state (vid, date, rec_fp) values (?, ?, ?) on conflict(vid) do update set rec_fp = excluded.rec_fp",
                       (v["vid"], v["date"], fp))
            stats["visit_list"] += 1
        if i % 500 == 499:
            st.commit()
    st.commit()

    # 6. Do'kon vizitisiz buyurtmalar: agentning o'sha kungi buyurtmasiz yopilgan vizitiga, bo'lmasa alohida.
    leftovers: dict[tuple, list[dict]] = defaultdict(list)
    for o in new_orders:
        if o["ext"] in sent_orders or now - o["t"] < LOOSE_AFTER:
            continue
        if o["visit"] is not None and not vstate.get(o["visit"]["vid"], {}).get("ordered"):
            continue  # o'z vizitining yopilishini kutadi
        leftovers[(o["agent"], o["date"])].append(o)
    for (agent_kid, date), group in leftovers.items():
        agent_uuid = agents[agent_kid]
        target = absorber(agent_kid, date, min(o["t"] for o in group))
        if target is not None:
            if send_money(target["vid"], agent_uuid, date, sum((o["amt"] for o in group), ZERO)):
                mark(target, "ordered")
            record_orders(group, target["vid"])
            stats["orders_other_visit"] += len(group)
        else:
            for o in group:
                vid = u5("kuzatuv-order", o["ext"])
                send_money(vid, agent_uuid, date, o["amt"])
                record_orders([o], vid)
                stats["orders_no_visit"] += 1
    st.commit()

    # 7. Tuzatishlar: yuborilgan buyurtmaning summasi o'zgargan yoki to'liq qaytarilgan.
    fixes: dict[tuple, list[tuple]] = defaultdict(list)
    for ext, date, agent_kid, agent_uuid, amount, nfix in st.execute(
            "select ext_id, date, agent_kid, agent_uuid, amount, fixes from order_sent where date >= ?", (start_iso,)).fetchall():
        if ext in current and current[ext] != Decimal(amount):   # Kuzatuv'dan yo'qolgan qator — e'tiborsiz (yuqoridagi izoh)
            fixes[(agent_kid, agent_uuid, date)].append((ext, current[ext], Decimal(amount), nfix))
    for (agent_kid, agent_uuid, date), items in fixes.items():
        target = absorber(agent_kid, date)
        vid = target["vid"] if target is not None else u5("kuzatuv-order-fix", f"{items[0][0]}:{items[0][3] + 1}")
        if send_money(vid, agent_uuid, date, sum((cur - old for _, cur, old, _ in items), ZERO)) and target is not None:
            mark(target, "ordered")
        for ext, cur, _, nfix in items:
            st.execute("update order_sent set amount = ?, fixes = ? where ext_id = ?", (str(cur), nfix + 1, ext))
        stats["order_fixes"] += len(items)

    report_sources(api, kz, f"Oxirgi {(today - start).days + 1} kun tekshirildi: {len(visits):,} vizit".replace(",", " "), iso(now), True)
    meta_set(st, "last_ok_date", today_iso)
    meta_set(st, "last_ok_at", iso(now))
    meta_set(st, "visit_list_since", start_iso if meta_get(st, "visit_list_since") is None else meta_get(st, "visit_list_since"))
    st.commit()
    stats["window_from"] = int(start.strftime("%Y%m%d"))
    stats["radius_m"] = int(radius)
    return stats


SOURCE_TITLES = {"ritm-orders": "RITM buyurtmalari", "ritm-photo": "RITM rasmlari"}


def report_sources(api: Api, kz: sqlite3.Connection | None, bridge_detail: str, bridge_at: str | None, bridge_ok: bool) -> None:
    """Panel uchun ma'lumot yangiligi: ko'prik o'zi va Kuzatuv'ning Linko/RITM'dan oxirgi o'qishi."""
    events = [{"source": "bridge", "title": "Kuzatuv ko'prigi", "syncedAt": bridge_at, "ok": bridge_ok, "detail": bridge_detail[:450]}]
    if kz is not None:
        try:
            row = kz.execute("select value from setting where key = 'sync.linko.visits'").fetchone()
            if row:
                v = json.loads(row[0])
                events.append({"source": "linko-visits", "title": "Linko vizit hisoboti", "syncedAt": v.get("at"),
                               "ok": not v.get("error"), "detail": str(v.get("error") or "")[:450]})
            for r in kz.execute("select source_id, last_success_at, last_error from sync_state where source_id in ('ritm-orders', 'ritm-photo')"):
                events.append({"source": r[0], "title": SOURCE_TITLES[r[0]], "syncedAt": r[1], "ok": not r[2], "detail": str(r[2] or "")[:450]})
        except (sqlite3.Error, ValueError, TypeError) as e:
            log.warning("Kuzatuv sync holati o'qilmadi: %s", e)
    for e in events:
        api.publish("SourceSynced", e)


def replay_masters(api: Api, st: sqlite3.Connection) -> Counter:
    """Agentlar (holatdagi izdan: ism|filial|faol) va kunlik reja (holatdagi aynan yuborilgan son) qayta yuboriladi."""
    stats: Counter = Counter()
    kz = sqlite3.connect(f"file:{KUZATUV_DB}?mode=ro", uri=True)
    try:
        refs = {r[0]: (r[1], r[2]) for r in kz.execute("select id, source_id, source_ref from agent")}
    finally:
        kz.close()
    for kid, fp in st.execute("select kuz_id, fp from agent_sent").fetchall():
        if kid not in refs:
            continue
        name, region, active = fp.rsplit("|", 2)
        uid = u5("kuzatuv-agent", f"{refs[kid][0]}:{refs[kid][1]}")
        api.publish("AgentProfileChanged", {"agentId": uid, "name": name, "region": region, "district": region, "active": active == "1"})
        stats["agents"] += 1
    for agent, date, planned in st.execute("select agent, date, planned from day_planned order by date").fetchall():
        api.publish("DayPlanned", {"agentId": agent, "date": date, "plannedVisits": planned})
        stats["plans"] += 1
    return stats


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stdout)
    if not DRY and (not USER or not PASS):
        log.error("BRIDGE_USER / BRIDGE_PASS sozlanmagan")
        return 2
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))  # to'xtatilganda holat saqlanib qoladi (finally)
    api, st = Api(), open_state()
    if "--replay-masters" in sys.argv:
        log.info("agentlar va reja qayta yuborildi: %s", dict(replay_masters(api, st)))
        return 0
    while True:
        started = time.time()
        api.sent.clear()
        api.t0 = started
        try:
            stats = run_once(api, st)
            log.info("o'tish tugadi %.1fs%s: %s | yuborildi: %s", time.time() - started, " (SINOV, yuborilmadi)" if DRY else "",
                     dict(stats), dict(api.sent) or "yangilik yo'q")
        except Exception as e:  # keyingi o'tishda davom etadi — holat bazasi yuborilganlarni eslab qoladi
            log.error("xato: %s", e)
            try:  # panelda «ko'prik ishlamayapti» ko'rinsin
                report_sources(api, None, f"Xato: {e}", meta_get(st, "last_ok_at"), False)
            except Exception as e2:
                log.error("holatni yuborib bo'lmadi: %s", e2)
        if EVERY <= 0:
            return 0
        time.sleep(EVERY)


if __name__ == "__main__":
    sys.exit(main())
