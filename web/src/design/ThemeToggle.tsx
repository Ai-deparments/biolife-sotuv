"use client";
import { useTheme } from "@/theme/ThemeProvider";
import { Moon, Sun } from "./Icons";

export function ThemeToggle({ size = 44 }: { size?: number }) {
  const { mode, setPref } = useTheme();
  const next = mode === "tong" ? "tun" : "tong";
  return (
    <button type="button" className="lg press" onClick={() => setPref(next)} aria-label={next === "tun" ? "Qorong'i rejim" : "Yorug' rejim"} title={mode === "tong" ? "Yorug'" : "Qorong'i"}
      style={{ width: size, height: size, display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
      {mode === "tong" ? <Moon /> : <Sun />}
    </button>
  );
}
