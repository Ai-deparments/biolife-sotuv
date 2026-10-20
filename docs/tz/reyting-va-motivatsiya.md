# TZ · Reyting va motivatsiya (modul `rating`)

Egasi: Sultan. Holat: v1 amalga oshirildi (`api/src/main/java/uz/biolife/rating`), testlar `RatingFlowTests`.

## 1. Maqsad

140 agentning haftalik reytingi hamma ko'radigan, lekin shaxsiy ma'lumot oshkor qilmaydigan tarzda; ikki xil pul (savdo foizi va suhbat bonusi) qoidalari aniq va o'zgartiriladigan; har agentga haftalik missiya; rahbarga kimni rag'batlantirish va kim tushib ketayotgani.

## 2. Kirish ma'lumotlari (voqealar, `shared.events`)

| Voqea | Nimani beradi | Nashr qiluvchi (kelajakda) |
|---|---|---|
| `AgentProfileChanged` | agent nomi, viloyat, hudud, faol/yo'q | territory |
| `VisitClosed` | vizit yopildi: 4 dalil to'liqmi, sabab bilanmi, shubhalimi, blok | visit |
| `AnalysisCompleted` | 0–100 ball, mezon bo'yicha ballar, aksiya aytildimi, bayroqlar, xulosa | analysis (worker orqali) |
| `OrderConfirmed` | Linko'dagi buyurtma summasi (so'm) va blok | integration/linko |

Hozircha `ingest` moduli `POST /api/v1/internal/events/{type}` orqali qabul qiladi. Qayta yetkazilgan voqea ikki marta hisoblanmaydi (vizit/analiz id bo'yicha).

## 3. Qoidalar

- **Hafta** dushanbadan yakshanbagacha (ISO), `Asia/Tashkent`.
- **Ball** = haftadagi tahlil qilingan suhbatlar o'rtachasi (butun son).
- **Reyting** ball bo'yicha kamayish tartibida; tenglikda dalillar foizi, keyin yopilgan vizitlar soni.
- **Trend** = joriy ball − o'tgan hafta balli.
- **Seriya** = oxirgi kunlardan orqaga ketganda ketma-ket kunlik o'rtacha ≥ `streakThreshold` (80) bo'lgan kunlar; yakshanba uzmaydi.
- **Savdo bonusi** = haftalik buyurtmalar summasi × `salesPercentBp` / 10 000 (standart 1,5 %).
- **Suhbat bonusi** = `conversationBonusUzs` (380 000), faqat uchala shart birga: o'rtacha ball ≥ 80, 4 dalil to'liq ≥ 90 %, haftada shubhali yozuv yo'q. Rad sababi matn bilan saqlanadi.
- **Zo'r keys** nomzodi: ball ≥ 90 va bayroq yo'q. Rahbar nashr qilgandan keyingina boshqalarga ko'rinadi.
- **Missiya**: 7 mezon ichida bajarilish ulushi eng past bo'lgani; matn = mezon nomi, joriy / maksimum, maslahat.
- **Qayta hisoblash**: dushanba 00:10 (o'tgan hafta yopiladi), har ish kuni 18:40 (joriy hafta), va o'qishda 5 daqiqadan eski bo'lsa.

## 4. Ko'rinish qoidalari (maxfiylik)

| Kim | Nimani ko'radi |
|---|---|
| Agent, o'zi (`/me/rating`) | ball, o'rin, trend, seriya, mezon kesimi, ikkala bonus va sababi, missiya, top-10, o'zining nashr qilingan keyslari |
| Agent, boshqa agent (`/rating/agents/{id}`) | ism, viloyat, o'rin, ball, trend, seriya, **faqat nashr qilingan** keyslar. Pul yo'q, mezon kesimi yo'q, vizitlar yo'q |
| Rahbar | hammasi + e'tibor ro'yxati, qoidalar, keyslarni nashr qilish |

## 5. API

```
GET  /api/v1/me/rating?week=                  MeRating
GET  /api/v1/rating/leaderboard?week=&region= LeaderboardRow[]
GET  /api/v1/rating/agents/{id}?week=         AgentCard (ochiq kartochka)
GET  /api/v1/panel/rating/attention?week=     {encourage[], falling[]}
GET  /api/v1/panel/rating/rules               RulesDto
PUT  /api/v1/panel/rating/rules               RulesDto (422 agar chegaradan tashqari)
GET  /api/v1/panel/best-cases?status=pending|published
POST /api/v1/panel/best-cases/{id}/publish
POST /api/v1/internal/rating/recompute?week=  (rahbar/servis)
```
Identifikatsiya hozircha `X-User-Id`, `X-User-Role` sarlavhalari (identity moduligacha). Ruxsat yo'q → 403, sarlavha yo'q → 401, RFC 9457.

## 6. Jadvallar (`V001__rating.sql`)

`rating_agent`, `rating_visit_fact` (vizit id PK), `rating_weekly` (agent+hafta unikal), `rating_rules` (bitta qator), `rating_best_case` (analysis_id unikal), `rating_mission` (agent+hafta unikal).

## 7. E'tibor ro'yxati mantiqi

- **Rag'batlantirish**: seriya ≥ 5 kun yoki ball ≥ 90.
- **Tushib ketyapti**: ball < `fallingBelow` (50), yoki trend ≤ −5, yoki 3 kundan beri kunlik o'rtacha < 50.

## 8. Tayyor mezoni

- `RatingFlowTests`: o'rin/trend/seriya/bonus hisoblanadi, `/me/rating` to'liq, ochiq kartochka pul va kesimni bermaydi va faqat nashr qilingan keysni ko'rsatadi, e'tibor ro'yxati, qoidalar validatsiyasi va rollar, 401.
- `ModularityTests.verify()` o'tadi.

## 9. Ochiq savollar (mijoz / arxitektura)

1. Savdo foizi mahsulot turiga qarab farq qiladimi? Hozir bitta foiz.
2. Suhbat bonusi haftalikmi yoki oylik? Hozir haftalik.
3. Reytingni hudud bo'yicha ko'rsatish kerakmi yoki faqat umumiy? Ikkalasi bor (`region` filtri).
4. Mezon og'irliklari analysis modulidan keladi; hozir `Criteria` statik ro'yxat (jami 100).
