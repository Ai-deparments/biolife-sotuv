# biolife-api

Spring Boot 4 · Java 21 · Modulith · PostgreSQL · Flyway. Modullar: `shared` (ochiq), `identity`, `ingest` (vaqtinchalik), `rating`, `reporting`.

```bash
# lokal DB: postgres 14+ localhost:5432, baza `biolife` (user sultan, parolsiz) yoki BIOLIFE_DB_* env
./mvnw spring-boot:run -Dspring-boot.run.profiles=local     # :8080, demo seed yoqiladi
# login (local seed: rahbar/rahbar123, agentlar: sardor, dilnoza, nilufar, akmal, jasur, olim / agent123)
TOK=$(curl -s -X POST -H 'Content-Type: application/json' -d '{"username":"rahbar","password":"rahbar123"}' localhost:8080/api/v1/auth/login | python3 -c 'import json,sys;print(json.load(sys.stdin)["accessToken"])')
curl -H "Authorization: Bearer $TOK" localhost:8080/api/v1/panel/analytics/overview
mvn test                                                    # Testcontainers: Docker (OrbStack/Docker Desktop) kerak
```

Hujjatlar: `../docs/loyiha-strukturasi.md`, `../docs/tz/*.md`, `../docs/adr/*.md`. Modul diagrammalari testdan keyin `target/spring-modulith-docs/`.
