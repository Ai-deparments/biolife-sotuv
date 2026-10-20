# ADR 001 · Modular monolith (Spring Modulith)

Holat: qabul · 2026-10-09

**Kontekst.** 10 modul, bitta jamoa, bitta VPS, pilot 5 agent. Mikroservis infratuzilmasi (servis-aro tarmoq, alohida deploy) hozir foyda bermaydi, lekin modullar chegarasi buzilsa keyin ajratib bo'lmaydi.

**Qaror.** Bitta Spring Boot ilova, har modul `uz.biolife.<modul>` paketi, `@ApplicationModule`, ichida `api / application / domain / infra`. `shared` ochiq modul (voqealar, vaqt, identifikatsiya, xato shakli). `ModularityTests.verify()` CI'da; buzilsa merge yo'q. Modullar aro aloqa: avval voqea, zarur bo'lsa `application` interfeysi, hech qachon entity/repository.

**Rad etildi.** Mikroservislar (erta), qatlamli monolit paketlarsiz (chegarasiz, 3 oyda chigallashadi).

**Oqibat.** Keyin har modulni alohida servisga chiqarish mumkin, chunki faqat voqea va interfeys orqali bog'langan. Har modul o'z jadvallarini o'zi yaratadi (Flyway, `V00N__<modul>.sql`).
