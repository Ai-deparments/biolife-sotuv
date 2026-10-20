# Rahbar boti (Telegram)

Savdo rahbariyati uchun AI yordamchi. Buyruqli menyu emas: rahbar oddiy so'z bilan so'raydi, bot API'dan kerakli
ma'lumotni yig'ib, Claude (BioLife Max obunasi: serverdagi `claude` CLI, ubuntu logini — rasm ishchisi bilan bir xil;
u ishlamasa `nova-llm-gateway`) shu ma'lumot asosida qisqa javob yozadi.
Ma'lumotda yo'q narsani o'ylab topmaydi.

Savol namunalari: «bugun kim kech keldi?», «Samarqandda polka rasmlari qanday?», «shu hafta rejani eng kam bajarganlar»,
«Suhrobning kechagi vizitlari», «сколько агентов вышло вчера?». Javob savol tilida (o'zbekcha lotin yoki ruscha).

Pastdagi yirik tugmalar — tez hisobot, model chaqirilmaydi:

| Tugma | Nima ko'rsatadi |
|---|---|
| 📊 Bugun / 📅 Kecha | ishlagan agentlar, vizit/reja, dalillar, shubhali, buyurtma, polka standarti, kech/erta |
| 📷 Polka nazorati | AI tekshirgan rasmlar: standart / kam / BioLife yo'q / xira / polka emas, eng ko'p «yo'q» agentlar va 3 ta namuna rasm AI izohi bilan |
| 👥 Agentlar | shu hafta: rejani eng yaxshi bajarganlar, buyurtma bo'yicha eng yaxshilar, e'tibor kerak bo'lganlar |
| ⏰ Ish vaqti | bugun kech boshlaganlar (09:30 dan keyin), kechqurun — erta tugatganlar (17:30 dan oldin) |
| 🗺 Hududlar | hudud bo'yicha qamrov, buyurtma ulushi, faol agentlar |
| 🌐 Panel | rahbar panelini ochish tugmasi (joriy tunnel manzili bilan) |

Kunlik (du–sha 18:30) va haftalik (dushanba 09:00) hisobotni API o'zi shu bot orqali `BIOLIFE_REPORT_CHAT_ID` ga
yuboradi: API faqat `sendMessage`/`sendDocument`, bot esa `getUpdates` ishlatadi — bitta token ikkalasiga yetadi.

## Ishga tushirish

1. @BotFather'da bot yaratiladi (yoki mavjudi beriladi) — token.
2. `~/biolife-sales/.env`:
   ```
   BIOLIFE_REPORT_BOT_TOKEN=<token>      # hisobot ham, bot ham shu token bilan
   BIOLIFE_REPORT_CHAT_ID=<chat id>      # hisobot boradigan chat (guruh bo'lsa — manfiy son)
   BOT_ALLOWED_IDS=<id1>,<id2>           # botdan so'ray oladigan rahbarlar
   ```
   ID'ni bilish: botga yozing — ruxsat bo'lmasa bot ID raqamingizni aytadi; guruhda `/id`.
3. `sudo systemctl restart biolife-sales-api biolife-sales-bot`

Kirish: faqat `BOT_ALLOWED_IDS` dagi foydalanuvchilar va `BIOLIFE_REPORT_CHAT_ID` dagi chatlar. Guruhda bot faqat
eslatilganda (@bot), unga javob qaytarilganda yoki tugma bosilganda gapiradi. Boshqalarga faqat ID raqamini aytadi.

API servis hisobi — ishchilar bilan bir xil (`AI_WORKER_USER`, `AI_WORKER_PASS`), bot faqat o'qiydi.
Claude limiti to'lsa yoki obuna logini tugasa, bot buni ochiq aytadi; tez hisobot tugmalari baribir ishlaydi.

Xizmat: `biolife-sales-bot.service`. Jurnal: `journalctl -u biolife-sales-bot -f`.
Sinov (Telegram'siz): `python3 bot.py --show bugun|kecha|polka|agentlar|vaqt|hududlar`, `python3 bot.py --ask "savol"`.
