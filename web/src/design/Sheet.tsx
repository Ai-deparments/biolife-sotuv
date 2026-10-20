"use client";
import { useEffect, type ReactNode } from "react";
import { createPortal } from "react-dom";

/** iOS bottom sheet on glass. Closes on backdrop tap or Escape. Drawn into <body>, so a blurred card around the opener cannot clip it. */
export function Sheet({ open, onClose, title, children, wide = false }: { open: boolean; onClose: () => void; title: string; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", k);
    document.body.style.overflow = "hidden";
    return () => { document.removeEventListener("keydown", k); document.body.style.overflow = ""; };
  }, [open, onClose]);
  if (!open || typeof document === "undefined") return null;
  return createPortal(
    <>
      <button type="button" className="sheet-bg" onClick={onClose} aria-label="Yopish" />
      <div className={`lg lgc sheet ${wide ? "wide" : ""}`} role="dialog" aria-modal="true" aria-label={title} style={{ background: "var(--glass-strong)" }}>
        <div className="grab" />
        <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 12, marginBottom: 12 }}>
          <div style={{ fontSize: 20, fontWeight: 800, letterSpacing: "-0.02em" }}>{title}</div>
          <button type="button" onClick={onClose} className="press btn" style={{ minHeight: 40, padding: "0 14px", fontSize: 14 }} aria-label="Yopish">Yopish</button>
        </div>
        {children}
      </div>
    </>,
    document.body,
  );
}
