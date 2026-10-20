# TZ · Analitika va rahbar paneli (modul `reporting`)

Egasi: Sultan. Holat: v1 amalga oshirildi (`api/src/main/java/uz/biolife/reporting`), testlar `ReportingFlowTests`.

## 1. Maqsad

Rahbar uchun uchta narsa: (1) ish vaqti statistikasi 9:00–18:00, dushanba–shanba; (2) vizit, suhbat, foto ko'rsatkichlari agent kesimida; (3) sotuv rahbariga muntazam hisobot (kunlik va haftalik), avtomatik va qo'lda.

## 2. Kirish ma'lumotlari

`AgentProfileChanged`, `DayPlanned` (kunlik reja), `VisitStarted` (Keldim vaqti), `VisitClosed`, `AnalysisCompleted`, `PhotoChecked`, `OrderConfirmed`. Har vizit darajasidagi voqea bir marta qo'llanadi (`report_event_seen`).

## 3. Ish vaqti qoidalari (`shared.time.WorkHours`)

- Oyna 09:00–18:00 `Asia/Tashkent`, yakshanba ish kuni emas.
- **Birinchi faollik** = kundagi birinchi `Keldim` yoki yopish; **oxirgi faollik** = oxirgisi.
- **Faol daqiqalar** = birinchi va oxirgi faollik oralig'ining ish oynasi bilan kesishmasi.
- **Kech boshlagan** = birinchi faollik 09:30 dan keyin. **Erta tugatgan** = oxirgi faollik 17:30 dan oldin.
- Oynadan tashqaridagi ish hisobga olinmaydi (faol daqiqa qo'shmaydi), lekin vizit sanaladi.

## 4. Agent kesimidagi ko'rsatkichlar (`report_agent_day`, kun × agent)

reja, boshlandi, yopildi, 4 dalil to'liq, sabab bilan, shubhali, ball yig'indisi/soni (o'rtacha), aksiya aytilgan soni (foiz), foto jami/standart (foiz), blok, buyurtma so'm, birinchi/oxirgi faollik, faol daqiqa, kech/erta belgilari.

Davr bo'yicha agent KPI = kunlik qatorlar yig'indisi; bajarilish = yopildi / reja.

## 4a. Kirish huquqi

Panel endpointlari faqat `MANAGER`/`ADMIN` roli bilan (JWT), aks holda 403; token yo'q → 401. Web: `/kirish` sahifasi, sessiya brauzerda, agent hisobi panelga kirsa «faqat rahbar uchun» ekrani. Identity: ADR 003.

## 5. API

```
GET  /api/v1/panel/analytics/overview?date=                     Overview (plitkalar)
GET  /api/v1/panel/analytics/agents?from&to&region              AgentKpi[] (ball bo'yicha)
GET  /api/v1/panel/analytics/agents/{id}?from&to                AgentSeries (kunlik qatorlar; agent o'zini ko'ra oladi)
GET  /api/v1/panel/analytics/work-hours?from&to&region          WorkHoursRow[] (kech boshlash bo'yicha)
POST /api/v1/panel/reports/send?type=daily|weekly&date=         DeliveryDto
GET  /api/v1/panel/reports                                      oxirgi 50 yuborish
```
Davr standart: joriy hafta (du–sha). 92 kundan uzun davr 422.

## 6. Hisobotlar

- **Kunlik** 18:30 (du–sha): faol agentlar, vizit reja/boshlandi/yopildi (4 dalil %), sabab bilan, shubhali, o'rtacha ball, aksiya %, foto standart %, kech/erta, blok va so'm.
- **Haftalik** dushanba 09:00 o'tgan hafta uchun: vizit/reja, suhbatlar va o'rtacha, blok, eng yaxshi 3, e'tibor kerak 3, ish vaqti buzilishi 3.
- Kanal: Telegram (`biolife.reports.telegram.bot-token` va `chat-id` berilsa), aks holda log. Har yuborish `report_delivery` da (SENT/FAILED, xato matni).
- Matn faqat raqamlar, AI prozasi yo'q; o'zbek tilida.

## 7. Jadvallar (`V002__reporting.sql`)

`report_agent`, `report_agent_day` (agent+sana unikal), `report_event_seen`, `report_delivery`.

## 8. Tayyor mezoni

- `ReportingFlowTests`: kech/erta va faol daqiqalar, overview plitkalari, agent KPI tartibi va foizlari, ish vaqti jadvali, agent faqat o'zini ko'radi, hisobot tuziladi-yuboriladi-yoziladi, tarix.
- `ModularityTests.verify()` o'tadi.

## 9. Ochiq savollar

1. Kech boshlash chegarasi 09:30 va erta tugatish 17:30 — mijoz tasdiqlaydimi?
2. Hisobot qabul qiluvchilar: bitta chat yoki hudud rahbarlari ham? Hozir bitta chat.
3. Materialized view'lar 140 agent × 1 yil uchun kerak emas (kunlik jadval yetadi); 1000+ agent bo'lsa qo'shiladi.
