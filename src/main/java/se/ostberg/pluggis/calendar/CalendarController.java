package se.ostberg.pluggis.calendar;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.ostberg.pluggis.config.PluggisProperties;

import java.util.Map;

@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    private final CalendarSyncService sync;
    private final PluggisProperties props;

    public CalendarController(CalendarSyncService sync, PluggisProperties props) {
        this.sync = sync;
        this.props = props;
    }

    /** Synka nu – från konfigurerad länk eller en angiven. */
    @PostMapping("/sync")
    public CalendarSyncService.SyncResult syncNow(@RequestParam(required = false) String url) {
        String target = url != null ? url : props.calendar() == null ? null : props.calendar().icalUrl();
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("Ingen iCal-länk konfigurerad (pluggis.calendar.ical-url)");
        }
        return sync.syncFromUrl(target);
    }

    /** Ladda upp innehållet i en .ics-fil (Content-Type: text/calendar eller text/plain). */
    @PostMapping(path = "/import", consumes = {"text/calendar", "text/plain"})
    public CalendarSyncService.SyncResult importIcs(@RequestBody String ics) {
        return sync.importIcs(ics);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
