package se.ostberg.pluggis.calendar;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    public record Subscription(String url, Boolean onlySchoolwork) {
    }

    private final CalendarSyncService sync;

    public CalendarController(CalendarSyncService sync) {
        this.sync = sync;
    }

    @GetMapping
    public CalendarSyncService.Status status() {
        return sync.status();
    }

    /** Spara en prenumeration (iCal/webcal-länk) och synka direkt. */
    @PutMapping
    public CalendarSyncService.SyncResult subscribe(@RequestBody Subscription body) {
        return sync.subscribe(body.url(), body.onlySchoolwork() == null || body.onlySchoolwork());
    }

    @DeleteMapping
    public ResponseEntity<Void> unsubscribe() {
        sync.unsubscribe();
        return ResponseEntity.noContent().build();
    }

    /** Slå ihop dubbletter bland kalenderimporterade läxor. */
    @PostMapping("/dedupe")
    public Map<String, Integer> dedupe() {
        return Map.of("merged", sync.mergeDuplicates());
    }

    /** Synka den sparade prenumerationen nu. */
    @PostMapping("/sync")
    public CalendarSyncService.SyncResult syncNow() {
        return sync.syncNow();
    }

    /** Förhandsgranska: .ics-innehåll i kroppen, eller ?url= för en länk. Inget sparas. */
    @PostMapping("/preview")
    public List<CalendarSyncService.PreviewEntry> preview(@RequestParam(required = false) String url,
                                                          @RequestBody(required = false) String ics) {
        if (url != null && !url.isBlank()) {
            return sync.preview(sync.fetch(url));
        }
        if (ics == null || !ics.contains("BEGIN:VCALENDAR")) {
            throw new IllegalArgumentException("Ingen iCal-kalender i filen");
        }
        return sync.preview(ics);
    }

    /**
     * Importera .ics-innehåll. {@code uids} = kommaseparerade UID:n att importera (tomt = alla
     * inom tidsfönstret). Content-Type text/calendar eller text/plain.
     */
    @PostMapping(path = "/import", consumes = {"text/calendar", "text/plain"})
    public CalendarSyncService.SyncResult importIcs(@RequestBody String ics,
                                                    @RequestParam(required = false) String uids,
                                                    @RequestParam(defaultValue = "false") boolean onlySchoolwork) {
        Set<String> selected = uids == null || uids.isBlank() ? null
                : Arrays.stream(uids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        return sync.importIcs(ics, selected, onlySchoolwork);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
