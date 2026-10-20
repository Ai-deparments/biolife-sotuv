import type { ComponentPropsWithoutRef, ElementType, ReactNode } from "react";

type Base<T extends ElementType> = { as?: T; className?: string; children?: ReactNode } & Omit<ComponentPropsWithoutRef<T>, "as" | "className" | "children">;

/** Glass capsule. `as` lets it render as a button, link or span. */
export function GlassPill<T extends ElementType = "div">({ as, className = "", children, ...rest }: Base<T>) {
  const Tag = (as ?? "div") as ElementType;
  const interactive = Tag === "button" || Tag === "a" || Tag === "label";
  return <Tag className={`lg ${interactive ? "press" : ""} ${className}`} {...rest}>{children}</Tag>;
}

/** Glass card (28px radius, blur 16px). Add `flat` after the 8th list row. */
export function GlassCard<T extends ElementType = "div">({ as, className = "", children, ...rest }: Base<T>) {
  const Tag = (as ?? "div") as ElementType;
  const interactive = Tag === "button" || Tag === "a";
  return <Tag className={`lg lgc ${interactive ? "press" : ""} ${className}`} {...rest}>{children}</Tag>;
}

export function ScoreBar({ value, label, max = 100, stacked = false }: { value: number; label?: string; max?: number; stacked?: boolean }) {
  const pct = `${Math.round((value / max) * 100)}%`;
  if (stacked) {
    return (
      <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 12 }}>
          <span style={{ fontSize: 14, fontWeight: 600, minWidth: 0 }}>{label}</span>
          <span className="num" style={{ fontSize: 16 }}>{value}<span className="dim" style={{ fontSize: 12, fontWeight: 600 }}>{max !== 100 ? ` / ${max}` : ""}</span></span>
        </div>
        <div className="bar"><i style={{ width: pct }} /></div>
      </div>
    );
  }
  return (
    <div style={{ display: "grid", gridTemplateColumns: label ? "minmax(72px, 110px) minmax(60px, 1fr) auto" : "1fr auto", gap: 12, alignItems: "center" }}>
      {label && <span style={{ fontSize: 14, fontWeight: 600, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{label}</span>}
      <div className="bar"><i style={{ width: pct }} /></div>
      <span className="num" style={{ fontSize: 16 }}>{value}</span>
    </div>
  );
}

export function Stat({ label, value, unit, sub, strong, icon }: { label: string; value: ReactNode; unit?: string; sub?: ReactNode; strong?: boolean; icon?: ReactNode }) {
  return (
    <GlassCard style={{ padding: "16px 18px", display: "flex", flexDirection: "column", gap: 6, minWidth: 0 }}>
      <div className={`lbl ${strong ? "" : "dim"}`} style={{ display: "flex", alignItems: "center", gap: 8 }}>{icon && <span style={{ color: "var(--accent)", display: "inline-flex" }}>{icon}</span>}{label}</div>
      <div className="num stat-num">{value}{unit && <span className="dim" style={{ fontSize: "0.6em", fontWeight: 600 }}>{unit}</span>}</div>
      {sub && <div className="dim" style={{ fontSize: 13 }}>{sub}</div>}
    </GlassCard>
  );
}
