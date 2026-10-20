/** Dates and numbers for the panel. The company works in Asia/Tashkent, whatever the viewer's own time zone. */
const TZ = "Asia/Tashkent";
const MONTHS = ["yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul", "avgust", "sentabr", "oktabr", "noyabr", "dekabr"];
const WEEKDAYS = ["yakshanba", "dushanba", "seshanba", "chorshanba", "payshanba", "juma", "shanba"];

/** Today in Tashkent as YYYY-MM-DD. */
export const today = () => new Date().toLocaleDateString("en-CA", { timeZone: TZ });

const parse = (iso: string) => { const [y, m, d] = iso.split("-").map(Number); return new Date(Date.UTC(y, m - 1, d)); };
const out = (d: Date) => d.toISOString().slice(0, 10);

export const addDays = (iso: string, n: number) => { const d = parse(iso); d.setUTCDate(d.getUTCDate() + n); return out(d); };
/** Monday of the week containing the date. */
export const weekStart = (iso: string) => { const d = parse(iso); const wd = (d.getUTCDay() + 6) % 7; d.setUTCDate(d.getUTCDate() - wd); return out(d); };
export const isSunday = (iso: string) => parse(iso).getUTCDay() === 0;

/** «13-oktabr, seshanba» */
export const dayLong = (iso: string) => { const d = parse(iso); return `${d.getUTCDate()}-${MONTHS[d.getUTCMonth()]}, ${WEEKDAYS[d.getUTCDay()]}`; };
/** «13-okt» */
export const dayShort = (iso: string) => { const d = parse(iso); return `${d.getUTCDate()}-${MONTHS[d.getUTCMonth()].slice(0, 3)}`; };
/** «5–10-oktabr» / «29-sentabr – 4-oktabr» */
export const range = (from: string, to: string) => {
  if (from === to) return dayLong(from);
  const a = parse(from), b = parse(to);
  return a.getUTCMonth() === b.getUTCMonth() ? `${a.getUTCDate()}–${b.getUTCDate()}-${MONTHS[b.getUTCMonth()]}` : `${a.getUTCDate()}-${MONTHS[a.getUTCMonth()]} – ${b.getUTCDate()}-${MONTHS[b.getUTCMonth()]}`;
};

/** Instant → «09:05» in Tashkent. */
export const hm = (instant?: string | null) => instant ? new Date(instant).toLocaleTimeString("ru-RU", { timeZone: TZ, hour: "2-digit", minute: "2-digit" }) : "—";

/** «3 daqiqa oldin» */
export const ago = (instant?: string | null) => {
  if (!instant) return "noma'lum";
  const min = Math.max(0, Math.round((Date.now() - new Date(instant).getTime()) / 60000));
  if (min < 1) return "hozirgina";
  if (min < 60) return `${min} daqiqa oldin`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h} soat oldin`;
  return `${Math.floor(h / 24)} kun oldin`;
};

/** 12345678 → «12 345 678» (non-breaking spaces). */
export const num = (n: number) => Math.round(n).toLocaleString("ru-RU").replace(/[\s ]/g, " ");
/** Short money for tight places: «660,9 mln». */
export const money = (n: number) => {
  const a = Math.abs(n);
  if (a >= 1e9) return `${(n / 1e9).toLocaleString("ru-RU", { maximumFractionDigits: 2 })} mlrd`;
  if (a >= 1e6) return `${(n / 1e6).toLocaleString("ru-RU", { maximumFractionDigits: 1 })} mln`;
  return num(n);
};
export const pct = (part: number, whole: number) => (whole > 0 ? Math.round((100 * part) / whole) : null);
/** Minutes → «7 soat 20 daq» */
export const duration = (min?: number | null) => {
  if (min == null) return "—";
  if (min < 1) return "1 daqiqadan kam";
  if (min < 60) return `${min} daq`;
  return `${Math.floor(min / 60)} soat${min % 60 ? ` ${min % 60} daq` : ""}`;
};
