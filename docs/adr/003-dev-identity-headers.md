# ADR 003 · Identity: login + JWT; dev sarlavhalari faqat local/test

Holat: qabul · 2026-10-09 (yangilandi)

**Kontekst.** Rahbar paneli faqat rahbar hisobidan ochilishi kerak. Agentlar keyin Telegram orqali kiradi, rahbarlar brauzerdan login/parol bilan.

**Qaror.** `identity` moduli: `identity_user` (BCrypt parol, rol), `POST /api/v1/auth/login` → HS256 JWT (`shared.security.JwtCodec`, 12 soat, sir `BIOLIFE_JWT_SECRET` ≥ 32 belgi). `CurrentUserResolver` avval `Authorization: Bearer`, keyin faqat `biolife.auth.dev-headers=true` bo'lsa `X-User-Id/X-User-Role` (local va test profillari). Rol tekshiruvi serverda (`CurrentUser.requireManager()` → 403); web qobiq faqat bo'sh panelni ko'rsatmaslik uchun yo'naltiradi. Telegram initData login shu modulga qo'shiladi, `CurrentUser` o'zgarmaydi.

**Rad etildi.** Spring Security to'liq filtr zanjiri (hozir ortiqcha, OAuth/SSO kerak bo'lganda kiritiladi), token'ni cookie'da saqlash (Mini App ichida cookie ishonchsiz; hozir localStorage + Bearer).

**Oqibat.** Prod'da `dev-headers=false` (standart) va kuchli sir majburiy; deploy checklist'da. Login urinishlari log'da, foydalanuvchi mavjudligi oshkor qilinmaydi (bir xil 401).
