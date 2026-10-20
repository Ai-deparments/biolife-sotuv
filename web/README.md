# BioLife Agent AI · web

Savdo agentlari uchun AI tizimining web ilovasi. Dizayn: «Tiniq osmon», iOS 26 uslubidagi muzlatilgan shisha (frosted glass) och ko'k-oq palitrada: yumshoq osmon foni (CSS, sekin suzuvchi rang dog'lari), oq shisha panellar (blur 22 px), to'q ko'k matn, bitta aksent ko'k (#1F6FFF). Yorug' rejim asosiy, qorong'i rejim tugma bilan.

## Ishga tushirish

```bash
npm install
npm run dev        # http://localhost:3000
npm run build && npm start
```

## Ekranlar

Agent (telefon, `/`):
- `/` Marshrut: hafta rejasi, bugungi do'konlar, 4 dalil (Joy · Ovoz · Rasm · Linko), «Keldim» tugmasi va masofa (real geolokatsiya).
- `/vizit/[id]` Vizit: 20 m radius tekshiruvi (`navigator.geolocation`), diktafon (`MediaRecorder` + jonli daraja), polka rasmi (kamera), Linko buyurtma, AI maslahat, sabab varag'i.
- `/hisobot` Kun yakuni: AI bormagan do'konlar uchun sabab so'raydi, keyin hisobot yuboriladi.
- `/ballarim` Reyting: mezonlar bo'yicha ballar, AI tavsiyasi, haftaning zo'r keysi, 140 agent reytingi.

Rahbar paneli (`/panel`): Bugungi vizitlar (filtr, shubhali, sabab bilan → Qabul), Agentlar va reyting, Suhbatlar (0–100, har ball dalil bilan: soniya + gap), Rasmlar (polka sanash, eski rasm, «sanay olmadim»), Qamrov (xarita vs Linko, hudud bo'yicha), Mezonlar (og'irliklar, jami 100).

## Tuzilma

- `src/app/globals.css` dizayn tokenlari va shisha retsepti (`.lg`, `.lgc`, dalil kataklari, sheet, jadval).
- `src/design/` komponentlar: GlassPill, GlassCard, ProofCell, ProofRow, ScoreBar, Stat, TabBar, Sheet, Background, ThemeToggle, Icons.
- `src/theme/ThemeProvider.tsx` yorug'/qorong'i rejim (`data-theme` = tong | tun).
- `src/data/types.ts` domen tiplari; `src/data/mock.ts` demo ma'lumotlar (backend ulanguncha).
- `src/store/visits.ts` agent kunining holati, localStorage'da saqlanadi; backend ulanganda `load`/`persist` API chaqiruviga almashadi, komponentlar o'zgarmaydi.
- `src/lib/geo.ts` haversine va geofence; `src/lib/useRecorder.ts` mikrofon.

## Hozircha demo bo'lgan joylar

- Ovoz tahlili bali yozuv to'xtagach 1.8 s dan keyin simulyatsiya qilinadi (AI worker ulanmagan).
- Linko «Tekshir» tugmasi webhookni taqlid qiladi.
- Radiusdan tashqarida bo'lsangiz «Demo» tugmasi joyni tasdiqlaydi (faqat ko'rsatuv uchun, ishlab chiqarishda olib tashlanadi).
