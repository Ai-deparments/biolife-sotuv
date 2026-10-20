"use client";
import type { ReactNode } from "react";
import { GlassCard } from "./Glass";
import { Back, Next, Search } from "./Icons";
import { addDays, dayLong, today } from "@/lib/format";

/** Empty state that says why there is nothing and what happens next — never a fake zero. */
export function Empty({ icon, title, children }: { icon?: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      {icon && <span className="empty-icon">{icon}</span>}
      <div style={{ fontSize: 17, fontWeight: 800 }}>{title}</div>
      {children && <div className="dim" style={{ fontSize: 14, maxWidth: 560 }}>{children}</div>}
    </div>
  );
}

export function ErrorCard({ error, onRetry }: { error: string; onRetry?: () => void }) {
  return (
    <GlassCard style={{ padding: "14px 18px", display: "flex", gap: 12, alignItems: "center", flexWrap: "wrap", background: "var(--danger-soft)" }}>
      <div style={{ flex: 1, minWidth: 200 }}>
        <div style={{ fontWeight: 800 }}>Ma&apos;lumot yuklanmadi</div>
        <div style={{ fontSize: 14 }}>{error}. Internet aloqasini tekshirib, «Qayta urinish»ni bosing.</div>
      </div>
      {onRetry && <button type="button" className="press btn" onClick={onRetry}>Qayta urinish</button>}
    </GlassCard>
  );
}

/** Filter chip with a count. */
export function Chip({ on, onClick, children, count, tone }: { on: boolean; onClick: () => void; children: ReactNode; count?: number; tone?: "warn" | "danger" }) {
  return (
    <button type="button" onClick={onClick} aria-pressed={on} className={`press chip ${on ? "is-on" : ""} ${tone ?? ""}`}>
      {children}{count !== undefined && <span className="chip-n">{count.toLocaleString("ru-RU")}</span>}
    </button>
  );
}

/** One day: ‹ 13-oktabr, seshanba › with a calendar input. */
export function DayPicker({ value, onChange }: { value: string; onChange: (d: string) => void }) {
  const t = today();
  return (
    <div className="daypick lg">
      <button type="button" className="press" aria-label="Oldingi kun" onClick={() => onChange(addDays(value, -1))}><Back width={18} height={18} /></button>
      <label style={{ position: "relative", display: "flex", alignItems: "center", gap: 8, fontWeight: 700, fontSize: 15, padding: "0 6px", cursor: "pointer" }}>
        {value === t ? "Bugun · " : value === addDays(t, -1) ? "Kecha · " : ""}{dayLong(value)}
        <input type="date" value={value} max={t} onChange={(e) => e.target.value && onChange(e.target.value)} aria-label="Sanani tanlash"
          style={{ position: "absolute", inset: 0, opacity: 0, cursor: "pointer" }} />
      </label>
      <button type="button" className="press" aria-label="Keyingi kun" disabled={value >= t} onClick={() => onChange(addDays(value, 1))}><Next width={18} height={18} /></button>
    </div>
  );
}

export type Period = { key: "today" | "yesterday" | "week" | "lastweek"; from: string; to: string; label: string };
export function periods(): Period[] {
  const t = today();
  const mon = (() => { const d = new Date(`${t}T00:00:00Z`); const wd = (d.getUTCDay() + 6) % 7; return addDays(t, -wd); })();
  return [
    { key: "today", from: t, to: t, label: "Bugun" },
    { key: "yesterday", from: addDays(t, -1), to: addDays(t, -1), label: "Kecha" },
    { key: "week", from: mon, to: t, label: "Shu hafta" },
    { key: "lastweek", from: addDays(mon, -7), to: addDays(mon, -2), label: "O'tgan hafta" },
  ];
}

export function PeriodPicker({ value, onChange }: { value: Period["key"]; onChange: (p: Period) => void }) {
  return (
    <div className="seg lg" role="group" aria-label="Davr">
      {periods().map((p) => (
        <button key={p.key} type="button" className={`press ${p.key === value ? "on" : ""}`} aria-pressed={p.key === value} onClick={() => onChange(p)}>{p.label}</button>
      ))}
    </div>
  );
}

export function SearchBox({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return (
    <label className="search">
      <Search width={18} height={18} />
      <input className="input" value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} aria-label={placeholder} />
    </label>
  );
}

export function RegionSelect({ value, regions, onChange }: { value: string; regions: string[]; onChange: (v: string) => void }) {
  return (
    <select className="input select" value={value} onChange={(e) => onChange(e.target.value)} aria-label="Filial">
      <option value="">Barcha filiallar</option>
      {regions.map((r) => <option key={r} value={r}>{r}</option>)}
    </select>
  );
}

/** Section title with an icon. */
export function SectionTitle({ icon, children, right }: { icon?: ReactNode; children: ReactNode; right?: ReactNode }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, justifyContent: "space-between", flexWrap: "wrap" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
        {icon && <span className="sec-icon">{icon}</span>}
        <div style={{ fontSize: 17, fontWeight: 800, letterSpacing: "-0.01em" }}>{children}</div>
      </div>
      {right}
    </div>
  );
}
