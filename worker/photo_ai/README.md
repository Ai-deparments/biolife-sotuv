# Polka rasmi tahlili ishchisi

Agent vizitda RITM orqali polka rasmini yuboradi, u Kuzatuv ko'prigi bilan `visit_record` ga tushadi. Bu ishchi API navbatidan
bir to'da vizitni oladi (`POST /api/v1/internal/photos/claim`) va:

1. rasmni yuklab, ffmpeg bilan kichraytiradi (uzun tomoni 1280 px; vizitdan ko'pi bilan 2 ta rasm, yangisi birinchi);
2. **Claude** (BioLife Max obunasi, serverdagi `claude` CLI orqali — `nova-llm-gateway` faqat matn qabul qiladi) bitta chaqiruvda
   to'dadagi hamma rasmni ko'radi: polka rasmimi, aniqmi, BioLife guruhi (BIOLIFE, FRUCTIS, Moxito, Time Tea, Sitro) mahsulotining
   nechta yuzi bor, qaysi brendlar, qaysi raqobatchilar joy egallagan, rahbarga bitta gap izoh;
3. natijani API'ga qaytaradi. API standart bilan solishtirib hukm chiqaradi va `PhotoChecked` voqeasini chiqaradi: kunlik
   hisobotdagi «Polka rasmi standarti» foizi va panelning «Rasmlar» sahifasi shundan.

| Hukm | Ma'nosi |
|---|---|
| `ok` — Standart | BioLife yuzlari standartdagi sondan kam emas |
| `low` — Kam | BioLife bor, lekin standartdan kam |
| `none` — BioLife yo'q | polkada BioLife mahsuloti ko'rinmaydi |
| `unclear` — Sanab bo'lmadi | rasm xira, qorong'i yoki juda uzoqdan |
| `not_shelf` — Polka emas | do'konning tashqi ko'rinishi, selfi va hokazo |

Standart (kamida nechta yuz, standart 1) va izoh panelning «Mezonlar va bonus» sahifasida o'zgartiriladi.

## Namuna va limitlar

Kuniga ~5 000 vizit rasm bilan keladi. Obuna boshqa agentlar bilan umumiy, shuning uchun hammasi emas, **namuna** tekshiriladi:
navbat bugungi va kechagi vizitlardan har agentdan teng ulush oladi (avval har agentning eng yangi viziti, keyin ikkinchisi ...),
shuning uchun foiz har agent uchun adolatli. Bugungi navbat tugamasa, kechagisi tunda ko'riladi.

- `PHOTO_AI_MAX_PER_HOUR` (standart 60) — soatiga nechta vizit. 60 da kuniga ~1 400 vizit (~28 %).
- Claude limiti to'lsa yoki login muddati tugasa, to'da navbatga qaytadi (urinish hisoblanmaydi), ishchi 30 daqiqa kutadi.
- RITM rasmni ~18–24 soatdan keyin o'chiradi (2026-10-18 da kechagi navbatdagi 3 074 vizit shu sababli «404» bo'ldi).
  Shuning uchun ishchining yon oqimi har 5 daqiqada Kuzatuv bazasidagi yangi rasmlarni `data/photo-cache/` ga yuklab
  qo'yadi (kuniga ~0,6 GB, 3 kun saqlanadi): kunduz ulgurilmagan vizitlar tunda keshdan tahlil qilinadi.
- Vizitdagi bitta rasm o'chgan bo'lsa qolganlari bilan tahlil qilinadi; hammasi o'chgan bo'lsa vizit «tekshirilmadi»
  bo'lib qoladi. Boshqa xatoda 4 marta qayta uriniladi.
- Holat panelning «Ma'lumot manbalari» oynasida «AI rasm tahlili» bo'lib ko'rinadi.

## Sozlash (`~/biolife-sales/.env`, hammasi ixtiyoriy)

```
PHOTO_AI_MODEL=sonnet          # Claude modeli (CLI taxallusi)
PHOTO_AI_MAX_PER_HOUR=60
PHOTO_AI_BATCH=5               # bir chaqiruvdagi vizitlar
PHOTO_AI_PER_VISIT=2
PHOTO_AI_MAX_SIDE=1280
PHOTO_CACHE_DIR=/home/ubuntu/biolife-sales/data/photo-cache
PHOTO_CACHE_DAYS=3
PHOTO_PREFETCH_SEC=300
```

Kesh uchun `KUZATUV_DB` kerak (ko'prik bilan bir xil, `.env` da bor). Kesh sinovi: `python3 worker.py --prefetch-once`.

API servis hisobi suhbat ishchisi bilan bir xil (`AI_WORKER_USER`, `AI_WORKER_PASS`). Claude login — `ubuntu` foydalanuvchisining
`~/.claude` dagi obuna logini (Kuzatuv ham shuni ishlatadi).

Xizmat: `biolife-sales-photo.service`. Jurnal: `journalctl -u biolife-sales-photo -f`.
Sinov (API'siz, faqat Claude): `python3 worker.py --try rasm1.jpg rasm2.jpg`.
