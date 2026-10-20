"use client";
import { useState } from "react";
import { ApiError, apiSend } from "@/data/api";
import { GlassPill } from "./Glass";
import { Sheet } from "./Sheet";

/** Change one's own password (manager panel and agent app). */
export function PasswordSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [form, setForm] = useState({ current: "", next: "", again: "" });
  const [state, setState] = useState<{ busy?: boolean; ok?: boolean; error?: string }>({});
  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    if (form.next.length < 8) { setState({ error: "Yangi parol kamida 8 belgi bo'lsin" }); return; }
    if (form.next !== form.again) { setState({ error: "Yangi parol ikki marta bir xil yozilmadi" }); return; }
    setState({ busy: true });
    try {
      await apiSend("POST", "/auth/password", { currentPassword: form.current, newPassword: form.next });
      setState({ ok: true }); setForm({ current: "", next: "", again: "" });
    } catch (err) { setState({ error: err instanceof ApiError ? err.message : "Saqlanmadi, qayta urinib ko'ring" }); }
  };
  return (
    <Sheet open={open} onClose={() => { setState({}); onClose(); }} title="Parolni o'zgartirish">
      <form onSubmit={save} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <label className="field"><span className="lbl dim">Hozirgi parol</span><input className="input" type="password" autoComplete="current-password" value={form.current} onChange={(e) => setForm({ ...form, current: e.target.value })} required /></label>
        <label className="field"><span className="lbl dim">Yangi parol (kamida 8 belgi)</span><input className="input" type="password" autoComplete="new-password" value={form.next} onChange={(e) => setForm({ ...form, next: e.target.value })} required /></label>
        <label className="field"><span className="lbl dim">Yangi parol yana bir marta</span><input className="input" type="password" autoComplete="new-password" value={form.again} onChange={(e) => setForm({ ...form, again: e.target.value })} required /></label>
        {state.error && <div style={{ color: "var(--danger)", fontWeight: 700 }}>{state.error}</div>}
        {state.ok && <div style={{ color: "#17803f", fontWeight: 700 }}>Parol o&apos;zgardi. Keyingi kirishda yangi parolni yozing.</div>}
        <GlassPill as="button" type="submit" className="primary" disabled={state.busy} style={{ height: 52, fontSize: 16, fontWeight: 800 }}>{state.busy ? "Saqlanmoqda…" : "Saqlash"}</GlassPill>
      </form>
    </Sheet>
  );
}
