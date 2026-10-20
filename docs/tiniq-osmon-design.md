# «Tiniq osmon» dizayn tizimi — Claude uchun ko'rsatma

> Bu faylni o'qigan Claude: quyidagi uslubni **aynan** shunday qo'llang. Rang, radius, blur va tugma qoidalari qat'iy. Ijod qilish kerak bo'lsa faqat kontent va joylashuvda, uslubda emas. Etalon amalga oshirish: `web/src/app/globals.css` + `web/src/design/`. Shu faylni ochib ko'chirish mumkin.

## 1. Kayfiyat bir jumlada

iOS 26 uslubidagi muzlatilgan shisha (frosted glass), **och ko'k va oq** palitra, yumshoq yorug'lik, tinch va minimal. Yorug' rejim asosiy. Qorong'i rejim bor, lekin faqat foydalanuvchi tugma bilan yoqsa.

Taqiqlar: fon rasmi yo'q (AI silk/lenta rasmlar yo'q), qora shisha yo'q, ko'p rang yo'q, emoji yo'q, qalin soyalar yo'q, o'tkir burchaklar yo'q, gradient yozuvlar yo'q, text-shadow yo'q.

## 2. Tokenlar (CSS o'zgaruvchilar)

Yorug' rejim (`:root`), standart:

| Token | Qiymat | Vazifa |
|---|---|---|
| `--text` | `#0e1f45` | asosiy matn, to'q ko'k |
| `--text-dim` | `rgba(14,31,69,.62)` | ikkinchi darajali matn |
| `--text-faint` | `rgba(14,31,69,.42)` | juda xira matn, punktir chegaralar |
| `--accent` | `#1f6fff` | yagona aksent, iOS ko'k |
| `--accent-2` | `#0d5ae8` | aksentning to'qroq varianti |
| `--accent-soft` | `rgba(31,111,255,.12)` | och ko'k fon (teglar, soft tugmalar) |
| `--accent-soft-2` | `rgba(31,111,255,.2)` | och ko'k, kuchliroq |
| `--danger` / `--danger-soft` | `#ff4d4f` / `rgba(255,77,79,.14)` | rad etilgan, xato |
| `--warn` / `--warn-soft` | `#ffb020` / `rgba(255,176,32,.22)` | sabab bilan, ogohlantirish |
| `--glass` | `rgba(255,255,255,.52)` | kapsula va tugma shishasi |
| `--glass-card` | `rgba(255,255,255,.6)` | kartochka shishasi |
| `--glass-strong` | `rgba(255,255,255,.78)` | sheet, ajratilgan kartochka |
| `--glass-on` | `rgba(255,255,255,.96)` | tanlangan tab, faol qator (deyarli oq) |
| `--fill` / `--fill-2` | `rgba(14,31,69,.05)` / `.08` | shisha ichidagi ichki bloklar, chiziqlar |
| `--hair` | `inset 0 1px 0 rgba(255,255,255,.9)` | yuqori qirra yorug'ligi |
| `--edge` | `rgba(255,255,255,.75)` | 1px oq chegara |
| `--shadow` | `0 14px 40px rgba(26,58,128,.14), 0 1px 2px rgba(26,58,128,.06)` | ko'tarilish soyasi (ko'k tusli, qora emas) |
| `--shadow-sm` | `0 6px 18px rgba(26,58,128,.12)` | kichik soya |
| `--ground` | `#dbe8ff` | sahifa foni |
| `--blob-a/b/c/d` | `#8fb8ff` `#c7f0ff` `#dcd6ff` `#ffffff` | fon dog'lari |
| `--r-pill` / `--r-card` | `999px` / `26px` | radiuslar |
| `--spring` | `cubic-bezier(.34,1.56,.64,1)` | iOS prujina |
| `--ease` | `cubic-bezier(.2,.8,.2,1)` | oddiy yumshoq |

Qorong'i rejim (`:root[data-theme="tun"]`): `--text #eef3ff`, `--accent #5b96ff`, `--glass rgba(22,36,72,.5)`, `--glass-card rgba(22,36,72,.58)`, `--glass-strong rgba(28,44,86,.78)`, `--glass-on rgba(255,255,255,.14)`, `--fill rgba(255,255,255,.07)`, `--fill-2 .12`, `--hair inset 0 1px 0 rgba(255,255,255,.18)`, `--edge rgba(255,255,255,.16)`, `--ground #0a1430`, bloblar `#1f3f8f #164a7a #3b2f7a #0f2252`, soyalar `rgba(0,8,30,.45)`.

Qoida: komponentlarda **hech qachon** qattiq rang yozilmaydi (`#fff`, `rgba(255,255,255,…)`), faqat tokenlar. Istisno: ko'k (`.primary`) tugma ichidagi oq matn va oq belgilar.

## 3. Fon

Rasm emas, CSS. Sahifa foni `--ground`, ustida 4 ta katta dumaloq rang dog'i, `filter: blur(70px)`, 26–44 soniyalik juda sekin `alternate` animatsiya bilan suzadi. `prefers-reduced-motion` da to'xtaydi.

```css
.bg { position: fixed; inset: 0; z-index: 0; overflow: hidden; background: var(--ground); }
.bg i { position: absolute; border-radius: 50%; filter: blur(70px); opacity: .9; }
.bg .b1 { width: 60vmax; height: 60vmax; left: -15vmax; top: -20vmax; background: var(--blob-a); animation: drift1 26s ease-in-out infinite alternate; }
.bg .b2 { width: 50vmax; height: 50vmax; right: -12vmax; top: 10vmax; background: var(--blob-b); animation: drift2 32s ease-in-out infinite alternate; }
.bg .b3 { width: 55vmax; height: 55vmax; left: 20vmax; bottom: -25vmax; background: var(--blob-c); animation: drift3 38s ease-in-out infinite alternate; }
.bg .b4 { width: 40vmax; height: 40vmax; right: 10vmax; bottom: 5vmax; background: var(--blob-d); opacity: .8; animation: drift2 44s ease-in-out infinite alternate-reverse; }
@keyframes drift1 { to { transform: translate(8vmax, 6vmax) scale(1.08); } }
@keyframes drift2 { to { transform: translate(-7vmax, 5vmax) scale(.94); } }
@keyframes drift3 { to { transform: translate(-6vmax, -8vmax) scale(1.06); } }
```
HTML: `<div class="bg" aria-hidden><i class="b1"></i><i class="b2"></i><i class="b3"></i><i class="b4"></i></div>`. Kontent `position: relative; z-index: 1` (`.z`).

## 4. Shisha retsepti

```css
.lg {                                   /* kapsula: tugma, tab bar, teg */
  position: relative; border-radius: var(--r-pill);
  background: var(--glass);
  -webkit-backdrop-filter: blur(22px) saturate(1.6); backdrop-filter: blur(22px) saturate(1.6);
  box-shadow: var(--hair), inset 0 0 0 1px var(--edge), var(--shadow);
  color: var(--text); isolation: isolate;
}
.lg::before {                           /* tepadan yorug'lik */
  content: ""; position: absolute; inset: 0; border-radius: inherit; pointer-events: none; z-index: -1;
  background: linear-gradient(180deg, rgba(255,255,255,.35) 0%, rgba(255,255,255,0) 55%);
}
.lgc { border-radius: var(--r-card); background: var(--glass-card); }   /* kartochka: .lg bilan birga */
.lgc.flat { backdrop-filter: none; background: var(--glass-strong); }   /* 8-qatordan boshlab blur o'chadi */
```
Ishlab chiqarish qoidasi: bitta ekranda blur qatlami 8 tadan oshmasin. `prefers-reduced-transparency` da hamma shisha `--glass-on` ga aylanadi.

## 5. Holatlar va tugmalar

```css
.press { transition: transform 420ms var(--spring), background-color 180ms var(--ease); }
.press:active { transform: scale(.965); transition-duration: 80ms; }        /* iOS bosish */
.press:disabled { color: var(--text-faint); }
.press:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.on { background: var(--glass-on) !important; box-shadow: var(--hair), inset 0 0 0 1px var(--edge), var(--shadow-sm); }  /* tanlangan */
.primary { background: var(--accent) !important; color: #fff !important;
  box-shadow: inset 0 1px 0 rgba(255,255,255,.35), 0 12px 30px rgba(31,111,255,.35); }   /* asosiy harakat */
.primary:disabled { background: var(--accent-soft-2) !important; color: var(--text-dim) !important; box-shadow: none; }
.soft { background: var(--accent-soft) !important; color: var(--accent) !important;
  box-shadow: inset 0 0 0 1px var(--accent-soft-2); backdrop-filter: none; }           /* kichik harakat */
```

Tugma ierarxiyasi, ekranda bittadan ortiq `.primary` bo'lmasin:
- **`.lg.primary`** — ekranning asosiy harakati, balandligi 64 px (telefon) yoki 44–56 px, `font-weight 800`, 17–19 px.
- **`.lg.soft`** — qator ichidagi harakat («Yozish», «Olish», «Sabab»), 40 px, 13 px, `font-weight 700`.
- **`.lg`** — ikkinchi darajali (filtr, «Haftalik hisobot»), 44 px.
- Ikonkali dumaloq tugma 40–44 px, `.lg`.

## 6. Tipografika

Shrift: `-apple-system, "SF Pro Display", Geist, system-ui, sans-serif`, `font-variant-numeric: tabular-nums`, antialiased. Matn soyasi yo'q.

| Rol | O'lcham / vazn | Qo'shimcha |
|---|---|---|
| Katta raqam (`.num`) | 28–44 px / 800 | `letter-spacing -.03em; line-height 1` |
| Sahifa sarlavhasi | 28 px telefon, 32 px panel / 800 | |
| Kartochka sarlavhasi | 15–16 px / 700 | |
| Matn | 15 px / 400–600 | `line-height 1.45` |
| Ikkinchi matn (`.dim`) | 12–13 px | `--text-dim` |
| Yorliq (`.lbl`) | 11 px / 700 | `letter-spacing .1em; uppercase` |

Minimal matn 11 px (faqat `.lbl`), oddiy matn 13 px dan kichik emas.

## 7. Komponentlar

- **GlassPill** (`.lg`, `as` = button/a/div) va **GlassCard** (`.lg.lgc`). Kartochka padding `12–16px 14–18px`, ichki `gap 8–12`.
- **Dalil katagi** (`.cell`): `sm` 20px/radius 7, `md` 40/14, `lg2` 44/14. Holatlar: `.c-done` ko'k to'ldirilgan, oq belgi; `.c-wait` punktir `--text-faint`; `.c-run` `--accent-soft-2` + ko'k 1px chegara, ichida pulsatsiya nuqta; `.c-exc` sariq; `.c-bad` qizil. Tartib doim: Joy · Ovoz · Rasm · Linko.
- **Teg** (`.tag`): `--accent-soft` fon, `--accent` matn, 10 px uppercase, radius 999.
- **Ichki blok** (`.sub`): `--fill` fon, `inset 0 0 0 1px var(--fill-2)`, radius 16.
- **ScoreBar**: 8 px chiziq, fon `--fill-2`, to'ldirish `--accent`. Uzun yorliqlar uchun «stacked» variant: yorliq va raqam tepada, chiziq pastda.
- **Stat**: GlassCard ichida `.lbl.dim` → `.num` 34 px → `.dim` 13 px izoh.
- **TabBar**: `.lg`, padding 5, 3 ta teng ustun, tanlangan ostida `--glass-on` oq kapsula `translateX` bilan `460ms var(--spring)` sirpanadi.
- **Sheet** (pastki varaq): `.lg.lgc.sheet`, fon `--glass-strong`, radius 32 tepada, `up 460ms var(--spring)`; orqa parda `rgba(14,31,69,.28)` + `blur(4px)`; tepada 40×5 px tutqich. Escape va parda bosilganda yopiladi.
- **Opt** (radio/checkbox kapsula): `--fill` fon, tanlanganda `.on` → `--accent-soft` fon, `--accent` matn, `accent-color: var(--accent)`.
- **Jadval** (`.tbl`): qatorlar 50 px, `border-top 1px var(--fill-2)`, hover `--fill`, keng jadval `.tbl-wrap` ichida gorizontal skroll.
- **Filtr pill** (`.pill`): 12 px uppercase, `--fill`; faol `.pill.on` to'liq ko'k.

## 8. Joylashuv

- Telefon ilova: bitta ustun, `max-width 430px`, yon `padding 16px`, `safe-area-inset` hisobga olinadi. Asosiy tugma + tab bar pastda `position: fixed`, ro'yxat ostida 110–150 px bo'sh joy.
- Boshqaruv paneli: `grid 232px + 1fr`, `max-width 1440`, gap 20. Chap panel `.lg.lgc` sticky, ichida guruhlar (`.lbl.dim` sarlavha + `.press` havolalar, faol `.on`). 900 px dan tor ekranda yon panel yashirinadi, burger tugma `.lg` 44 px, menyu chapdan `GlassCard` bo'lib chiqadi.
- Statistika kartochkalari `repeat(auto-fit, minmax(190px, 1fr))`, gap 12. Asosiy kontent `2fr + 1fr`, 1100 px da bitta ustunga tushadi.
- Bo'shliqlar: kartochkalar orasi 8 px (telefon ro'yxati), 12–16 px (panel).

## 9. Rejim almashtirish

`<html data-theme="tong|tun">`. Standart `tong`. Tanlov `localStorage` (`*.theme`) da. `<head>` dagi kichik inline skript hidratsiyadan oldin `data-theme` ni qo'yadi. Toggle: dumaloq `.lg` 40–44 px, ikonka oy/quyosh (inline stroke SVG, emoji emas). `theme-color` meta: `#dbe8ff` / `#0a1430`.

## 10. Ikonkalar

Faqat inline SVG, `stroke="currentColor" stroke-width 2.4 round`, 18–20 px. Emoji taqiqlanadi. Ikonkali tugmada `aria-label` majburiy.

## 11. Tekshiruv ro'yxati (topshirishdan oldin)

1. Ekranda bittadan ortiq to'liq ko'k tugma yo'q.
2. Komponentlarda qattiq rang yo'q, faqat tokenlar.
3. Blur qatlamlari ≤ 8, 8-qatordan `.flat`.
4. Matn kontrasti: `--text` oq shishada 4.5:1 dan yuqori; `--text-dim` faqat 13 px+ da.
5. Bosish effekti (`.press`) har bir tugma va havolada.
6. Telefon kengligida (390–430) gorizontal skroll yo'q, sheet va fixed tugmalar `safe-area` ni hisobga oladi.
7. `prefers-reduced-motion` va `prefers-reduced-transparency` ishlaydi.
8. Qorong'i rejimda ham hamma holat o'qiladi (tokenlar orqali avtomatik).

## 12. Minimal boshlang'ich fayl

Yangi loyihada: `web/src/app/globals.css` ni to'liq nusxalang (tokenlar + retseptlar tayyor), `web/src/design/` komponentlari va `web/src/theme/ThemeProvider.tsx` ni oling. React bo'lmasa, CSS ning o'zi yetadi: `.lg`, `.lgc`, `.press`, `.on`, `.primary`, `.soft`, `.cell`, `.tag`, `.sub`, `.bar`, `.sheet`, `.pill`, `.tbl` klasslari oddiy HTML da ham ishlaydi.
