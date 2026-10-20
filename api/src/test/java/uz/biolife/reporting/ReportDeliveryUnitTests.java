package uz.biolife.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uz.biolife.reporting.application.Report;
import uz.biolife.reporting.application.ReportComposer;
import uz.biolife.reporting.application.ReportWorkbook;
import uz.biolife.reporting.application.ReportingDtos.AgentKpi;
import uz.biolife.reporting.application.ReportingDtos.Overview;
import uz.biolife.reporting.application.ReportingDtos.WorkHoursRow;
import uz.biolife.reporting.application.TelegramReportSender;

/** Report text, the Excel copy and the Telegram calls, without Spring. */
class ReportDeliveryUnitTests {
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    final List<AgentKpi> kpis = List.of(
        new AgentKpi(a, "Akmal <Agent> & Co", "Самарканд", 1, 30, 24, 80, 30, 0, 2, 0, 0, 0, 0, 0, 0, 12_345_678L, 420, 0, 1),
        new AgentKpi(b, "Dilnoza Agent", "Андижон", 0, 25, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0, 0, 0));
    final List<WorkHoursRow> hours = List.of(new WorkHoursRow(a, "Akmal <Agent> & Co", "Самарканд", 1, 1, "09:05", "16:40", 420, 0, 1));
    final Overview o = new Overview(LocalDate.of(2026, 10, 13), 1, 2, 55, 24, 24, 30, 0, 2, 0, 0, 0, 0, 1, 12_345_678L, 0, 0, 0);

    @Test
    void dailyReportIsHtmlWithEscapedNamesAndNoEmptyMetrics() {
        Report r = new ReportComposer(new ReportWorkbook()).daily(o, kpis, hours);
        assertThat(r.html()).contains("<b>Kunlik hisobot</b> · <b>13-oktabr</b>, seshanba")
            .contains("Akmal &lt;Agent&gt; &amp; Co").contains("<b>12 345 678</b> so'm")
            .contains("Rejasi bor, lekin vizit yo'q: <b>1</b> agent (Dilnoza Agent)")
            .doesNotContain("Suhbat:").doesNotContain("Polka rasmi");
        assertThat(r.text()).contains("Akmal <Agent> & Co").doesNotContain("<b>");
        assertThat(r.fileName()).isEqualTo("BioLife-kunlik-2026-10-13.xlsx");
    }

    @Test
    void excelCopyIsAValidWorkbook() throws Exception {
        byte[] xlsx = new ReportWorkbook().agents("Kunlik hisobot", kpis, hours);
        assertThat(xlsx).startsWith((byte) 'P', (byte) 'K');
        java.nio.file.Path f = java.nio.file.Files.createTempFile("report", ".xlsx");
        java.nio.file.Files.write(f, xlsx);
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(f.toFile())) {   // reads the central directory, as Excel does
            ZipEntry sheet = zip.getEntry("xl/worksheets/sheet1.xml");
            assertThat(sheet).isNotNull();
            String xml = new String(zip.getInputStream(sheet).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            ZipEntry shared = zip.getEntry("xl/sharedStrings.xml");
            String strings = shared == null ? "" : new String(zip.getInputStream(shared).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(xml + strings).contains("Akmal &lt;Agent&gt; &amp; Co").contains("Jami").contains("12345678").doesNotContain("NaN");
        } finally {
            java.nio.file.Files.deleteIfExists(f);
        }
    }

    @Test
    void telegramGetsHtmlMessageAndDocumentForEveryChat() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (String chat : List.of("111", "-222")) {
            server.expect(requestTo("https://tg.test/botTOKEN/sendMessage")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.chat_id").value(chat)).andExpect(jsonPath("$.parse_mode").value("HTML"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://tg.test/botTOKEN/sendDocument")).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        }
        Report r = new ReportComposer(new ReportWorkbook()).daily(o, kpis, hours);
        new TelegramReportSender("TOKEN", "111, -222", "https://tg.test/", builder).send(r);
        server.verify();
    }
}
