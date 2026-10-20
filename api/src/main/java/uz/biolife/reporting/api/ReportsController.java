package uz.biolife.reporting.api;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.biolife.reporting.application.ReportService;
import uz.biolife.reporting.application.ReportingDtos.DeliveryDto;
import uz.biolife.reporting.domain.ReportDelivery;
import uz.biolife.reporting.infra.ReportDeliveryRepository;
import uz.biolife.shared.security.CurrentUser;

/** Manual send and history of reports to the head of sales. */
@RestController
@RequestMapping("/api/v1/panel/reports")
public class ReportsController {
    private final ReportService reports;
    private final ReportDeliveryRepository deliveries;
    private final Clock clock;

    public ReportsController(ReportService reports, ReportDeliveryRepository deliveries, Clock clock) {
        this.reports = reports; this.deliveries = deliveries; this.clock = clock;
    }

    @PostMapping("/send")
    public DeliveryDto send(CurrentUser user, @RequestParam(defaultValue = "daily") String type,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        user.requireManager();
        LocalDate d = date == null ? LocalDate.now(clock) : date;
        return toDto("weekly".equalsIgnoreCase(type) ? reports.sendWeekly(d) : reports.sendDaily(d));
    }

    /** Where reports go now: "telegram" or "log" (no bot configured yet). */
    @GetMapping("/settings")
    public java.util.Map<String, String> settings(CurrentUser user) {
        user.requireManager();
        return java.util.Map.of("channel", reports.channel());
    }

    @GetMapping
    public List<DeliveryDto> history(CurrentUser user) {
        user.requireManager();
        return deliveries.findTop50ByOrderBySentAtDesc().stream().map(ReportsController::toDto).toList();
    }

    private static DeliveryDto toDto(ReportDelivery r) {
        return new DeliveryDto(r.getId(), r.getType(), r.getPeriodStart(), r.getPeriodEnd(), r.getChannel(), r.getRecipient(), r.getStatus(), r.getError(), r.getSentAt(), r.getBody());
    }
}
