# Kuzatuv ko'prigi

Biolife Kuzatuv (Linko/RITM'dan to'ldiriladigan SQLite) faqat o'qiladi. Haqiqiy dala agentlari, kunlik reja,
Linko vizitlari va buyurtmalar `POST /api/v1/internal/events/{type}` orqali voqea bo'lib API'ga keladi.
Serverda `biolife-sales-bridge.service` sifatida har 5 daqiqada ishlaydi. Yuborilganlar `state.sqlite` da.
Qoidalar va sozlamalar `bridge.py` boshidagi izohda.

Aniqlik: har kun uchun savdo, yopilgan vizitlar, «uzoq» vizitlar, reja va faol agentlar Kuzatuv bilan birma-bir mos
(2026-10-13 da 14 kun tekshirilgan). Buyurtma keyin o'zgarsa (summa, to'liq qaytarish) tuzatish yuboriladi.

Rahbar paneli uchun yana ikki voqea yuboriladi: `VisitRecorded` (vizitlar ro'yxati — do'kon, vaqt, masofa, reja, rasmlar
havolasi bilan, shu do'kondagi buyurtmalar; o'zgarsagina qayta) va `SourceSynced` (ko'prik, Linko vizitlari, RITM buyurtma va
rasmlari qachon yangilangani — panelning «Yangilandi» belgisi shundan).

API'ga agentlar yoki rejani o'qiydigan yangi modul qo'shilsa, ularni bir marta aynan avvalgi qiymatlar bilan qayta yuborish:

```bash
cd ~/biolife-sales/worker/kuzatuv_bridge && set -a && . ../../.env && set +a && python3 bridge.py --replay-masters
```

Kuzatuv'da yo'q dalillar: rasmning AI tekshiruvi va blok soni — panel foto standarti ustunida «—» yozadi.
Suhbat bali Kuzatuv'dan emas, agent ilovasidagi yozuvdan keladi (`worker/conversation_ai`); ko'prik yuborgan `VisitRecorded`
yozuvni vaqti bo'yicha do'konga bog'lash uchun ham ishlatiladi.

Agent login'i keyinchalik yaratilsa, uning id'si ko'prik bergan agent UUID bilan bir xil bo'lishi kerak
(`uuid5(NS, "kuzatuv-agent:<source_id>:<source_ref>")`), aks holda agent o'z reytingini ko'rmaydi.

Hammasini qaytadan yuklash (masalan qoidalar o'zgarsa):

```bash
sudo systemctl stop biolife-sales-bridge
# API faktlari: rating_* (rating_rules dan tashqari), report_*, visit_*, source_status, event_publication jadvallarini truncate qilish
rm ~/biolife-sales/worker/kuzatuv_bridge/state.sqlite
sudo systemctl start biolife-sales-bridge   # ~8 daqiqa, ~150 ming voqea
```
