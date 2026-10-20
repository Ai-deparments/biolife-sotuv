# ADR 002 · Voqealar: Modulith event registry (outbox) + Redis Streams tashqariga

Holat: qabul · 2026-10-09

**Kontekst.** rating va reporting faqat faktlarni iste'mol qiladi. Faktlar vizit/analiz modullaridan va Python worker'dan keladi. Yo'qolgan yoki ikki marta kelgan voqea reytingni buzadi.

**Qaror.** Ilova ichida `ApplicationEventPublisher` + Spring Modulith event publication registry (`event_publication` jadvali, JDBC, sxemani o'zi yaratadi): voqea tranzaksiya bilan birga saqlanadi, listener'lar `@ApplicationModuleListener` (async, alohida tranzaksiya), restart'da qayta yetkaziladi. Har iste'molchi idempotent: vizit/analiz id bo'yicha. Tashqi worker bilan aloqa Redis Streams (keyingi bosqich), shartnoma `shared.events`.

**Rad etildi.** Kafka (bitta VPS uchun og'ir), to'g'ridan-to'g'ri servis chaqiruvi (modullar bog'lanadi, reporting vizit modulini kutib qoladi).

**Oqibat.** Listener ichida xato bo'lsa voqea "incomplete" bo'lib qoladi va restart'da qayta uriniladi; shuning uchun listener'lar tashqi tarmoqqa chiqmaydi.
