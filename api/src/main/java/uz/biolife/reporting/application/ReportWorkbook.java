package uz.biolife.reporting.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.stereotype.Component;
import uz.biolife.reporting.application.ReportingDtos.AgentKpi;
import uz.biolife.reporting.application.ReportingDtos.WorkHoursRow;

/** The Excel copy of a report: one row per agent, sorted by sales, with a total row. */
@Component
public class ReportWorkbook {
    private static final String HEAD = "DCE8FF";

    public byte[] agents(String title, List<AgentKpi> kpis, List<WorkHoursRow> hours) {
        Map<UUID, WorkHoursRow> wh = ReportComposer.byAgent(hours);
        boolean conversations = kpis.stream().anyMatch(k -> k.conversations() > 0);
        List<String> cols = new java.util.ArrayList<>(List.of("№", "Agent", "Filial", "Ishlagan kun", "Reja", "Vizit", "Bajarilish, %",
            "Dalillar to'liq, %", "Uzoqdan ochilgan", "Savdo, so'm", "O'rtacha keldi", "O'rtacha ketdi", "Faol vaqt, soat", "Kech boshlagan", "Erta tugatgan"));
        if (conversations) { cols.add("Suhbatlar"); cols.add("O'rtacha ball"); }
        List<AgentKpi> rows = kpis.stream().sorted(Comparator.comparingLong(AgentKpi::salesUzs).reversed().thenComparing(AgentKpi::name)).toList();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Workbook wb = new Workbook(out, "BioLife Agent AI", "1.0");
            Worksheet ws = wb.newWorksheet("Agentlar");
            ws.value(0, 0, title);
            ws.style(0, 0).bold().fontSize(13).set();
            for (int c = 0; c < cols.size(); c++) {
                ws.value(2, c, cols.get(c));
                ws.style(2, c).bold().fillColor(HEAD).wrapText(true).verticalAlignment("center").set();
            }
            int r = 3;
            long planned = 0, closed = 0, suspect = 0, sales = 0;
            for (AgentKpi k : rows) {
                WorkHoursRow w = wh.get(k.agentId());
                int c = 0;
                ws.value(r, c++, r - 2);
                ws.value(r, c++, k.name());
                ws.value(r, c++, k.region());
                ws.value(r, c++, k.daysWorked());
                ws.value(r, c++, k.planned());
                ws.value(r, c++, k.closed());
                ws.value(r, c++, k.planned() == 0 ? null : (Number) k.completionRate());
                ws.value(r, c++, k.closed() == 0 ? null : (Number) k.proofsCompleteRate());
                ws.value(r, c++, k.suspect());
                ws.value(r, c, k.salesUzs()); ws.style(r, c++).format("#,##0").set();
                ws.value(r, c++, w == null ? null : w.avgFirstActivity());
                ws.value(r, c++, w == null ? null : w.avgLastActivity());
                ws.value(r, c, k.daysWorked() == 0 ? null : (Number) (k.avgActiveMinutes() / 60.0)); ws.style(r, c++).format("0.0").set();
                ws.value(r, c++, k.lateStarts());
                ws.value(r, c++, k.earlyFinishes());
                if (conversations) { ws.value(r, c++, k.conversations()); ws.value(r, c, k.conversations() == 0 ? null : (Number) k.avgScore()); }
                planned += k.planned(); closed += k.closed(); suspect += k.suspect(); sales += k.salesUzs();
                r++;
            }
            ws.value(r, 1, "Jami");
            ws.value(r, 4, planned);
            ws.value(r, 5, closed);
            ws.value(r, 6, planned == 0 ? null : (Number) Math.round(100.0 * closed / planned));
            ws.value(r, 8, suspect);
            ws.value(r, 9, sales);
            ws.range(r, 0, r, cols.size() - 1).style().bold().fillColor(HEAD).set();
            ws.style(r, 9).bold().fillColor(HEAD).format("#,##0").set();
            double[] widths = {5, 32, 18, 10, 8, 8, 11, 11, 11, 16, 10, 10, 10, 10, 10, 10, 10};
            for (int c = 0; c < cols.size(); c++) ws.width(c, widths[Math.min(c, widths.length - 1)]);
            ws.freezePane(2, 3);
            wb.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
