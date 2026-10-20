#!/bin/bash
# BioLife Agent AI — kunlik zaxira: Postgres bazasi (pg_dump, custom format) va Kuzatuv ko'prigi holati (sqlite .backup).
# backups/ papkasida 14 kun saqlanadi. Tiklash: pg_restore --clean -d biolife_sales <fayl>.dump ; holat: gunzip va state.sqlite o'rniga.
set -euo pipefail
umask 077   # zaxirada parol xeshlari va savdo ma'lumoti bor: faqat egasi o'qiydi
D=/home/ubuntu/biolife-sales
OUT="$D/backups"
mkdir -p "$OUT"
STAMP=$(date +%F_%H%M)
set -a; . "$D/.env"; set +a
PGPASSWORD="$BIOLIFE_DB_PASSWORD" pg_dump -h 127.0.0.1 -U "$BIOLIFE_DB_USER" -Fc -f "$OUT/biolife_sales-$STAMP.dump.part" biolife_sales
mv "$OUT/biolife_sales-$STAMP.dump.part" "$OUT/biolife_sales-$STAMP.dump"
if [ -f "$D/worker/kuzatuv_bridge/state.sqlite" ]; then
  sqlite3 "$D/worker/kuzatuv_bridge/state.sqlite" ".backup '$OUT/bridge-state-$STAMP.sqlite'"
  gzip -f "$OUT/bridge-state-$STAMP.sqlite"
fi
find "$OUT" -type f \( -name 'biolife_sales-*.dump' -o -name 'bridge-state-*.sqlite.gz' \) -mtime +14 -delete
find "$OUT" -type f -name '*.part' -mmin +120 -delete
echo "zaxira tayyor: $(ls -1 "$OUT" | wc -l) fayl, $(du -sh "$OUT" | cut -f1)"
