package uz.biolife.visit.api;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.biolife.shared.security.CurrentUser;
import uz.biolife.shared.time.WorkHours;
import uz.biolife.visit.application.PhotoCheckService;
import uz.biolife.visit.application.PhotoCheckService.Settings;
import uz.biolife.visit.application.VisitDtos.AgentVisitRow;
import uz.biolife.visit.application.VisitDtos.RegionRow;
import uz.biolife.visit.application.VisitDtos.VisitPage;
import uz.biolife.visit.application.VisitQueries;

/** Manager visit list, branch coverage, per-agent visit checks and the shelf standard the photo AI checks against. */
@RestController
@RequestMapping("/api/v1/panel/visits")
public class VisitController {
    public record PhotoSettingsBody(Integer expectedFacings, String standardText) {}

    private final VisitQueries queries;
    private final PhotoCheckService photos;
    private final Clock clock;

    public VisitController(VisitQueries queries, PhotoCheckService photos, Clock clock) { this.queries = queries; this.photos = photos; this.clock = clock; }

    /** kind = all | far | outplan | noorder | nophoto | withphoto | oldphoto | photook | photobad; newest first. */
    @GetMapping
    public VisitPage list(CurrentUser user, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @RequestParam(defaultValue = "all") String kind, @RequestParam(required = false) String region,
                          @RequestParam(required = false) String q, @RequestParam(required = false) UUID agentId,
                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        user.requireManager();
        return queries.list(date, kind, region, q, agentId, page, size);
    }

    @GetMapping("/regions")
    public List<RegionRow> regions(CurrentUser user, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        user.requireManager();
        LocalDate today = LocalDate.now(clock);
        LocalDate f = from == null ? WorkHours.weekStart(today) : from;
        LocalDate t = to == null ? today : to;
        if (t.isBefore(f)) throw new IllegalArgumentException("Davr oxiri boshidan oldin bo'lmasin");
        if (f.plusDays(92).isBefore(t)) throw new IllegalArgumentException("Davr 92 kundan oshmasin");
        return queries.regions(f, t);
    }

    @GetMapping("/agents")
    public List<AgentVisitRow> agents(CurrentUser user, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                      @RequestParam(required = false) String region) {
        user.requireManager();
        return queries.agents(date, region);
    }

    @GetMapping("/photo-settings")
    public Settings photoSettings(CurrentUser user) {
        user.requireManager();
        return photos.settings();
    }

    @PutMapping("/photo-settings")
    public Settings updatePhotoSettings(CurrentUser user, @RequestBody PhotoSettingsBody body) {
        user.requireManager();
        return photos.updateSettings(body.expectedFacings(), body.standardText());
    }
}
