package se.ostberg.pluggis.gcal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.ostberg.pluggis.student.StudentService;

import java.util.Map;

/** Föräldravyn: koppla vald elevs plan till en Google Kalender. */
@RestController
@RequestMapping("/api/google-calendar")
public class GoogleCalendarController {

    public record Link(String calendarId) {
    }

    private final PlanCalendarSync sync;
    private final StudentService students;

    public GoogleCalendarController(PlanCalendarSync sync, StudentService students) {
        this.sync = sync;
        this.students = students;
    }

    @GetMapping
    public Map<String, Object> status() {
        return sync.status();
    }

    /** Spara kalender-id och synka direkt (så att fel syns på en gång). */
    @PutMapping
    public Map<String, Object> link(@RequestBody Link body) {
        if (!sync.enabled()) {
            throw new IllegalStateException("Google Kalender är inte konfigurerat – lägg in tjänstekontots JSON-nyckel först");
        }
        String id = body.calendarId() == null ? "" : body.calendarId().trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Ange kalender-id");
        }
        var s = students.current();
        s.setGoogleCalendarId(id);
        students.save(s);
        var r = sync.sync();
        Map<String, Object> m = new java.util.HashMap<>(sync.status());
        m.put("result", r);
        return m;
    }

    @PostMapping("/sync")
    public Map<String, Object> syncNow() {
        var r = sync.sync();
        Map<String, Object> m = new java.util.HashMap<>(sync.status());
        m.put("result", r);
        return m;
    }

    /** Koppla bort: kommande pass tas bort ur kalendern. */
    @DeleteMapping
    public Map<String, Object> unlink() {
        var s = students.current();
        int removed = 0;
        try {
            removed = sync.clearUpcoming(s, s.getGoogleCalendarId());
        } catch (RuntimeException ignored) {
            // kalendern kanske inte finns längre – koppla bort ändå
        }
        s.setGoogleCalendarId(null);
        students.save(s);
        Map<String, Object> m = new java.util.HashMap<>(sync.status());
        m.put("removed", removed);
        return m;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> failed(IllegalStateException e) {
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
