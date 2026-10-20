"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useMyScore } from "@/data/api";

const tabs = [
  { href: "/", label: "Bugun" },
  { href: "/ballarim", label: "Ballarim" },
];

/** Glass tab bar with a sliding highlight (iOS 26 morph). */
export function TabBar({ rating }: { rating?: number }) {
  const path = usePathname();
  const myScore = useMyScore();
  const shown = rating ?? myScore;
  const idx = Math.max(0, tabs.findIndex((t) => (t.href === "/" ? path === "/" : path.startsWith(t.href))));
  return (
    <nav className="lg" aria-label="Asosiy" style={{ padding: 5, display: "grid", gridTemplateColumns: `repeat(${tabs.length}, minmax(0,1fr))`, gap: 4, position: "relative" }}>
      <span aria-hidden style={{ position: "absolute", top: 5, bottom: 5, left: 5, width: `calc((100% - 10px - ${(tabs.length - 1) * 4}px) / ${tabs.length})`, borderRadius: 999, background: "var(--glass-on)", boxShadow: "var(--hair)", transform: `translateX(calc(${idx} * (100% + 4px)))`, transition: "transform 460ms var(--spring)" }} />
      {tabs.map((t, i) => (
        <Link key={t.href} href={t.href} aria-current={i === idx ? "page" : undefined} className="press"
          style={{ position: "relative", height: 46, borderRadius: 999, display: "flex", alignItems: "center", justifyContent: "center", fontSize: 15, fontWeight: i === idx ? 800 : 600 }}>
          {t.label}{t.href === "/ballarim" && shown != null ? ` · ${shown}` : ""}
        </Link>
      ))}
    </nav>
  );
}
