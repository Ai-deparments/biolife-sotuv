package uz.biolife.reporting.application;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import uz.biolife.reporting.application.ReportingDtos.AgentKpi;
import uz.biolife.reporting.application.ReportingDtos.Overview;
import uz.biolife.reporting.application.ReportingDtos.WorkHoursRow;

/**
 * Reports in Uzbek for the head of sales: bold numbers, one fact per line, an emoji at the start, a field explained
 * in plain words the first time, «—» instead of a zero when there is no data. Numbers only, no AI prose.
 * Lines without a data source (conversation score, shelf standard) are left out until that source exists.
 */
@Component
public class ReportComposer {
    static final int TOP = 3;
    static final String LINE = "━━━━━━━━━━━━━━━━━━━━";
    private static final String[] MONTHS = {"yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul", "avgust", "sentabr", "oktabr", "noyabr", "dekabr"};
    private static final String[] WEEKDAYS = {"dushanba", "seshanba", "chorshanba", "payshanba", "juma", "shanba", "yakshanba"};

    private final ReportWorkbook workbook;

    public ReportComposer(ReportWorkbook workbook) { this.workbook = workbook; }

    public Report daily(Overview o, List<AgentKpi> kpis, List<WorkHoursRow> hours) {
        StringBuilder h = new StringBuilder();
        h.append("📊 <b>Kunlik hisobot</b> · <b>").append(day(o.date())).append("</b>, ").append(weekday(o.date())).append('\n').append(LINE).append('\n');
        totals(h, o);
        topSales(h, kpis);
        attention(h, kpis, hours, false);
        h.append("\n<i>Manba: Linko va RITM (Kuzatuv orqali). Agentlar bo'yicha to'liq jadval — Excel faylda.</i>");
        String title = "Kunlik hisobot " + o.date();
        return new Report(title, Html.strip(h.toString()), h.toString(), "BioLife-kunlik-" + o.date() + ".xlsx",
            workbook.agents(title, kpis, hours), "📎 Agentlar kesimi · " + day(o.date()));
    }

    public Report weekly(LocalDate from, LocalDate to, Overview o, List<AgentKpi> kpis, List<WorkHoursRow> hours) {
        StringBuilder h = new StringBuilder();
        h.append("📈 <b>Haftalik hisobot</b> · <b>").append(range(from, to)).append("</b>\n").append(LINE).append('\n');
        totals(h, o);
        topSales(h, kpis);
        attention(h, kpis, hours, true);
        h.append("\n<i>Manba: Linko va RITM (Kuzatuv orqali). Agentlar bo'yicha to'liq jadval — Excel faylda.</i>");
        String title = "Haftalik hisobot " + from + " – " + to;
        return new Report(title, Html.strip(h.toString()), h.toString(), "BioLife-haftalik-" + from + "_" + to + ".xlsx",
            workbook.agents(title, kpis, hours), "📎 Agentlar kesimi · " + range(from, to));
    }

    private static void totals(StringBuilder h, Overview o) {
        h.append("👥 Ishlagan agentlar: <b>").append(n(o.agentsActive())).append("</b> / ").append(n(o.agentsTotal())).append('\n');
        h.append("🗺 Vizitlar (bajarildi / reja): <b>").append(n(o.closed())).append("</b> / ").append(n(o.planned()));
        if (o.planned() > 0) h.append(" · <b>").append(pct(o.closed(), o.planned())).append("%</b>");
        h.append('\n');
        h.append("✅ Dalillar to'liq (joyida + rasm + buyurtma): <b>").append(o.closed() == 0 ? "—" : o.proofsCompleteRate() + "%").append("</b>\n");
        h.append("📍 Do'kondan uzoqda ochilgan vizit: <b>").append(n(o.suspect())).append("</b>\n");
        if (o.conversations() > 0) h.append("🎙 Suhbat: o'rtacha <b>").append(o.avgScore()).append("</b> ball, aksiya aytildi <b>").append(o.promoRate()).append("%</b>\n");
        if (o.photosTotal() > 0) h.append("🖼 Polka rasmi standarti: <b>").append(o.photosOkRate()).append("%</b>\n");
        h.append("⏰ Ishni kech boshlagan (09:30 dan keyin): <b>").append(n(o.lateStarts())).append("</b>\n");
        h.append("🏁 Ishni erta tugatgan (17:30 gacha): <b>").append(n(o.earlyFinishes())).append("</b>\n");
        h.append("💰 Savdo: <b>").append(n(o.salesUzs())).append("</b> so'm\n");
    }

    private static void topSales(StringBuilder h, List<AgentKpi> kpis) {
        List<AgentKpi> top = kpis.stream().filter(k -> k.salesUzs() > 0)
            .sorted(Comparator.comparingLong(AgentKpi::salesUzs).reversed()).limit(TOP).toList();
        if (top.isEmpty()) return;
        h.append("\n🏆 <b>Savdo bo'yicha eng yaxshi ").append(top.size()).append("</b>\n");
        int i = 1;
        for (AgentKpi k : top) h.append(i++).append(". ").append(Html.esc(k.name())).append(" · ").append(Html.esc(k.region())).append(" — <b>").append(n(k.salesUzs())).append("</b> so'm\n");
    }

    private static void attention(StringBuilder h, List<AgentKpi> kpis, List<WorkHoursRow> hours, boolean week) {
        StringBuilder a = new StringBuilder();
        List<AgentKpi> idle = kpis.stream().filter(k -> k.planned() > 0 && k.closed() == 0).toList();
        if (!idle.isEmpty()) {
            a.append("• Rejasi bor, lekin vizit yo'q: <b>").append(idle.size()).append("</b> agent");
            a.append(" (").append(idle.stream().limit(TOP).map(k -> Html.esc(k.name())).collect(Collectors.joining(", "))).append(idle.size() > TOP ? " va boshqalar" : "").append(")\n");
        }
        kpis.stream().filter(k -> k.planned() > 0 && k.closed() > 0).sorted(Comparator.comparingInt(AgentKpi::completionRate)).limit(TOP)
            .filter(k -> k.completionRate() < 70)
            .forEach(k -> a.append("• ").append(Html.esc(k.name())).append(" · ").append(Html.esc(k.region())).append(" — reja <b>").append(k.completionRate()).append("%</b> bajarildi\n"));
        kpis.stream().filter(k -> k.suspect() > 0).sorted(Comparator.comparingInt(AgentKpi::suspect).reversed()).limit(TOP)
            .forEach(k -> a.append("• ").append(Html.esc(k.name())).append(" — uzoqdan ochilgan vizit <b>").append(k.suspect()).append("</b>\n"));
        if (week) {
            hours.stream().filter(w -> w.lateStarts() + w.earlyFinishes() > 0)
                .sorted(Comparator.comparingInt((WorkHoursRow w) -> w.lateStarts() + w.earlyFinishes()).reversed()).limit(TOP)
                .forEach(w -> a.append("• ").append(Html.esc(w.name())).append(" — kech <b>").append(w.lateStarts()).append("</b>, erta <b>").append(w.earlyFinishes()).append("</b> kun\n"));
        }
        if (a.length() > 0) h.append("\n⚠️ <b>E'tibor kerak</b>\n").append(a);
    }

    static String n(long v) { return String.format(java.util.Locale.ROOT, "%,d", v).replace(',', '\u00a0'); }
    static int pct(int part, int whole) { return whole == 0 ? 0 : Math.round(100f * part / whole); }
    static String day(LocalDate d) { return d.getDayOfMonth() + "-" + MONTHS[d.getMonthValue() - 1]; }
    static String weekday(LocalDate d) { return WEEKDAYS[d.getDayOfWeek().getValue() - 1]; }
    static String range(LocalDate from, LocalDate to) {
        return from.getMonthValue() == to.getMonthValue() ? from.getDayOfMonth() + "–" + day(to) : day(from) + " – " + day(to);
    }

    /** Agent rows joined with their work-hours rows (same agents, same period). */
    static Map<UUID, WorkHoursRow> byAgent(List<WorkHoursRow> hours) {
        return hours.stream().collect(Collectors.toMap(WorkHoursRow::agentId, Function.identity(), (x, y) -> x));
    }
}
