#!/bin/bash
# BioLife Agent AI — serverda yig'ish va ishga tushirish.
#   ./build.sh api   API (Maven)        → app/api/biolife-api-<vaqt>.jar, app/api/current.jar havolasi, API qayta yonadi
#   ./build.sh web   web (Next, standalone) → app/web-<vaqt>, app/web havolasi, web qayta yonadi
#   ./build.sh       ikkalasi
# Ishlab turgan servis qayta yongunicha eski fayllarida qoladi: yig'ish xato bo'lsa hech narsa o'zgarmaydi.
# Yangi versiya sog'lom javob bermasa, oldingisiga avtomatik qaytadi. Oxirgi 3 ta versiya saqlanadi.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
STAMP=$(date +%Y%m%d-%H%M%S)
WHAT=${1:-all}
mkdir -p app/api

if [[ $WHAT == api || $WHAT == all ]]; then
  echo "== API $(date) =="
  (cd api && MAVEN_OPTS=-Xmx700m ./mvnw -B -q --no-transfer-progress -DskipTests package)
  install -m 644 api/target/biolife-api-0.1.0-SNAPSHOT.jar "app/api/biolife-api-$STAMP.jar"
  ln -sfn "biolife-api-$STAMP.jar" app/api/current.jar
  sudo -n systemctl restart biolife-sales-api
  for i in $(seq 1 60); do curl -sf "http://127.0.0.1:${PORT:-8130}/actuator/health" >/dev/null && break; sleep 2; done
  if ! curl -sf "http://127.0.0.1:${PORT:-8130}/actuator/health" >/dev/null; then
    echo "API sog'lom emas — oldingi versiyaga qaytarilmoqda"
    prev=$(ls -1t app/api/biolife-api-*.jar | sed -n 2p)
    [ -n "$prev" ] && ln -sfn "$(basename "$prev")" app/api/current.jar && sudo -n systemctl restart biolife-sales-api
    exit 1
  fi
  ls -1t app/api/biolife-api-*.jar | tail -n +4 | xargs -r rm -f
  echo "API tayyor: $(readlink app/api/current.jar)"
fi

if [[ $WHAT == web || $WHAT == all ]]; then
  echo "== WEB $(date) =="
  (cd web && npm ci --no-audit --no-fund --loglevel=error && NEXT_PUBLIC_BUILD_ID="$STAMP" npm run build)
  D="app/web-$STAMP"
  mkdir -p "$D/.next"
  cp -a web/.next/standalone/. "$D/"
  cp -a web/.next/static "$D/.next/static"
  [ -d web/public ] && cp -a web/public "$D/public"
  ln -sfn "web-$STAMP" app/web
  sudo -n systemctl restart biolife-sales-web
  for i in $(seq 1 30); do curl -sf -o /dev/null "http://127.0.0.1:${WEB_PORT:-3130}/kirish" && break; sleep 1; done
  if ! curl -sf -o /dev/null "http://127.0.0.1:${WEB_PORT:-3130}/kirish"; then
    echo "web javob bermayapti — oldingi versiyaga qaytarilmoqda"
    prev=$(ls -1dt app/web-* | sed -n 2p)
    [ -n "$prev" ] && ln -sfn "$(basename "$prev")" app/web && sudo -n systemctl restart biolife-sales-web
    exit 1
  fi
  ls -1dt app/web-* | tail -n +4 | xargs -r rm -rf
  echo "web tayyor: $(readlink app/web)"
fi
echo "== TAYYOR $(date) =="
