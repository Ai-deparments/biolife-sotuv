# BioLife Agent AI

Savdo agentlari uchun AI tizimi. Monorepo: `web/` (Next.js 16: rahbar paneli va agent ilovasi), `api/` (Spring Boot 4, modular monolith), `worker/` (Python: Kuzatuv ko'prigi, AI suhbat va polka rasmi tahlili), `deploy/`, `docs/`.

Boshlash: `docs/loyiha-strukturasi.md` (15-bo'lim), dizayn: `docs/tiniq-osmon-design.md`, modul TZ: `docs/tz/`, qarorlar: `docs/adr/`.

```bash
# api (lokal postgres kerak, baza biolife)
cd api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# web
cd web && npm install && npm run dev
```

## Serverda (production)

BioLife serveri, WSL Ubuntu, `/home/ubuntu/biolife-sales`. Ma'lumot manbasi — Kuzatuv (Linko va RITM): `worker/kuzatuv_bridge`.

| Servis | Nima qiladi |
|---|---|
| `biolife-sales-api` | API, `127.0.0.1:8130`, `app/api/current.jar` |
| `biolife-sales-web` | rahbar paneli, `127.0.0.1:3130`, `app/web` (Next standalone) |
| `biolife-sales-tunnel` | HTTPS manzil (Cloudflare tezkor tunneli; manzil o'zgarsa API hisobot chatiga e'lon qiladi) |
| `biolife-sales-bridge` | Kuzatuv → API, har 5 daqiqada |
| `biolife-sales-ai` | suhbat tahlili: Gemini faqat nutqni matnga o'giradi, Claude (Max obuna, `nova-llm-gateway`) baholaydi — `worker/conversation_ai` |
| `biolife-sales-photo` | polka rasmi tahlili: Claude (Max obuna, `claude` CLI) BioLife yuzlarini sanab standart bilan solishtiradi; RITM rasmni ~1 kunda o'chirgani uchun yangi rasmlarni keshga oldindan yuklaydi — `worker/photo_ai` |
| `biolife-sales-bot` | rahbar boti (Telegram): savolga AI javob (Claude CLI, zaxira — `nova-llm-gateway`) va tez hisobot tugmalari — `worker/telegram_bot` |
| `biolife-sales-backup.timer` | har kuni 02:45 zaxira → `backups/` (14 kun) |

```bash
./build.sh api      # yoki web, yoki ikkalasi: yangi versiya alohida yig'iladi, sog'lom bo'lmasa oldingisiga qaytadi
```

Sozlamalar `.env` da (namuna: `deploy/.env.example`). Telegram: `BIOLIFE_REPORT_BOT_TOKEN`, `BIOLIFE_REPORT_CHAT_ID`
(bir nechta chat — vergul bilan) va `BOT_ALLOWED_IDS` (botdan so'ray oladigan rahbarlar), keyin
`sudo systemctl restart biolife-sales-api biolife-sales-bot`. Token bo'sh bo'lsa hisobot faqat jurnalga yoziladi. Batafsil: `worker/telegram_bot/README.md`.

API testlari Docker'dagi Postgres bilan: `sudo su ubuntu -g docker -c './mvnw test'` (api papkasida).

### Suhbat tahlili

Agent telefonida o'sha manzilni ochadi (login rahbar panelidagi «Foydalanuvchilar → Agentga login berish» dan) va do'konda
«Suhbatni yozish» ni bosadi. Yozuv har soniya telefonga saqlanadi (internet yoki sahifa uzilsa ham yo'qolmaydi), keyin
`data/recordings/` ga yuklanadi. `biolife-sales-ai` uni matnga o'giradi va 7 mezon bo'yicha baholaydi; ball yozuv vaqtiga to'g'ri
kelgan Linko vizitiga bog'lanib reyting va kunlik hisobotga tushadi. Rahbar «Suhbatlar» sahifasida ball, dalil, matn va ovozni ko'radi,
joriy aksiya va yangi mahsulot matnini «Mezonlar va bonus» da yozadi.

- Ishga tushirish uchun `.env` dagi `GEMINI_API_KEY` ga Google AI Studio kaliti yoziladi va `sudo systemctl restart biolife-sales-ai`.
  Kalitsiz yozuvlar navbatda kutadi, panelda sababi ko'rinadi.
- Nutqsiz yoki tushunarsiz yozuv baholanmaydi va reytingga kirmaydi. Haftalik suhbat bonusini faqat «savdo suhbati emas» bayrog'i to'xtatadi.
- Ovoz 180 kun saqlanadi (`BIOLIFE_RECORDINGS_KEEP_DAYS`), keyin o'chadi; matn va ball bazada qoladi. 212 agent to'liq yozsa kuniga
  ~4 GB bo'ladi — u holda muddatni 60–90 kunga tushirish kerak.
- Batafsil: `worker/conversation_ai/README.md`.
