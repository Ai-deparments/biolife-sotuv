# Suhbat tahlili ishchisi

Agent telefonida yozilgan do'kon suhbati API navbatiga tushadi (`POST /api/v1/me/recordings`). Bu ishchi navbatdan
bittadan oladi va:

1. ffmpeg bilan 16 kHz mono Opus'ga siqadi (30 daqiqa ≈ 5 MB, 30 daqiqadan oshgani kesiladi);
2. **Gemini** (`gemini-3.5-transcribe`) — faqat nutqni matnga o'giradi: so'zlovchilarga ajratadi (spk_1, spk_2), vaqt
   belgilari bilan, til ishorasi `uz-UZ, ru-RU`. Fayl Files API orqali yuklanadi va tahlildan keyin darhol o'chiriladi;
3. **Claude** (BioLife Max obunasi, serverdagi `nova-llm-gateway` orqali) — kim agent ekanini aniqlaydi, 7 mezon bo'yicha
   baholaydi (salom 10, ehtiyoj 20, taklif 20, aksiya 15, yangi 10, e'tiroz 15, yakun 10), har muhim ballni iqtibos va
   vaqt bilan asoslaydi, agentga bitta maslahat yozadi;
4. natijani API'ga qaytaradi. API ballni chegaralab qayta hisoblaydi, yozuvni vaqti bo'yicha Linko viziti bilan
   bog'laydi va `AnalysisCompleted` voqeasini chiqaradi: reyting, kunlik hisobot va panelning «Suhbatlar» sahifasi shundan.

Joriy aksiya va yangi mahsulot matni panelning «Mezonlar» sahifasida yoziladi va har tahlilga qo'shiladi.

## Kvota va limitlar

- Gemini bepul limiti tugasa (429) yoki Claude obuna limiti to'lsa, yozuv navbatda qoladi, urinish hisoblanmaydi va ishchi
  limit yangilanguncha yangi yozuv olmaydi. Kunlik Gemini kvotasi Tinch okeani vaqti bilan yarim tunda yangilanadi.
- `AI_MAX_PER_HOUR` (standart 60) — Max obunani boshqa agentlar bilan bo'lishish uchun soatlik chegara.
- Bir yozuv 6 marta muvaffaqiyatsiz bo'lsa «tahlil qilinmadi» holatiga o'tadi; panelda qayta yuborish tugmasi bor.
- Ishchi holati panelning «Ma'lumot manbalari» oynasida «AI suhbat tahlili» bo'lib ko'rinadi (kalit yo'qligi, limit,
  oxirgi tahlil vaqti).

## Sozlash (`~/biolife-sales/.env`)

```
AI_WORKER_USER=ai-worker            # API servis hisobi (ADMIN)
AI_WORKER_PASS=...
GEMINI_API_KEY=...                  # aistudio.google.com/apikey
GEMINI_MODEL=gemini-3.5-transcribe
GEMINI_LANGUAGES=uz-UZ,ru-RU
LLM_GATEWAY_URL=http://127.0.0.1:4195
LLM_GATEWAY_TOKEN=...               # /home/claude/secrets/llm-gateway.env dagi bilan bir xil
AI_MAX_PER_HOUR=60
```

Ixtiyoriy: `GEMINI_WORD_TIMESTAMPS=0` (so'z vaqtlari o'chiq, matn biroz aniqroq, dalilda vaqt bo'lmaydi),
`GEMINI_UPLOAD=inline` (Files API o'rniga so'rov ichida), `CLAUDE_MODEL` (shlyuz standarti o'rniga).

Xizmat: `biolife-sales-ai.service`. Jurnal: `journalctl -u biolife-sales-ai -f`.

## Maxfiylik

Gemini bepul tarifida yuborilgan ma'lumot Google mahsulotlarini yaxshilash uchun ishlatilishi mumkin (Google shartlari).
Pullik tarifda (daqiqasiga $0.003) bunday emas — faqat kalit almashtiriladi, kod o'zgarmaydi.
Ovoz fayli serverda `BIOLIFE_RECORDINGS_DIR` da 180 kun saqlanadi (`BIOLIFE_RECORDINGS_KEEP_DAYS`), keyin o'chiriladi;
matn, ball va dalillar bazada qoladi.
