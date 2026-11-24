package se.ostberg.pluggis.session;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.ostberg.pluggis.planning.PlanningService;
import se.ostberg.pluggis.student.StudentContext;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tid i Öva räknas som plugg. Appen säger till när en övning börjar och slutar och hur länge eleven var aktiv;
 * servern räknar aldrig mer än tiden som faktiskt gått sedan start (och högst en timme).
 */
@RestController
@RequestMapping("/api/practice")
public class PracticeController {

    static final Duration MAX = Duration.ofMinutes(60);

    private final StudySessionService sessions;
    private final PlanningService planning;
    private final Map<String, Started> open = new ConcurrentHashMap<>();

    public PracticeController(StudySessionService sessions, PlanningService planning) {
        this.sessions = sessions;
        this.planning = planning;
    }

    record Started(Long student, String what, Long homeworkId, Instant at) {
    }

    public record StartRequest(String what, Long homeworkId) {
    }

    public record EndRequest(Long activeSeconds) {
    }

    @PostMapping("/start")
    public Map<String, String> start(@RequestBody StartRequest body) {
        Instant now = Instant.now();
        open.values().removeIf(s -> s.at().isBefore(now.minus(Duration.ofHours(3))));
        String id = UUID.randomUUID().toString();
        String what = body.what() == null || body.what().isBlank() ? "Övning" : body.what().trim();
        open.put(id, new Started(StudentContext.currentOrDefault(), what.length() > 120 ? what.substring(0, 120) : what,
                body.homeworkId(), now));
        return Map.of("id", id);
    }

    /**
     * Svar: hur många minuter som räknades, och ett moment i dagens plan för samma läxa som eleven kan bocka av.
     */
    @PostMapping("/{id}/end")
    public ResponseEntity<?> end(@PathVariable String id, @RequestBody EndRequest body, @RequestParam(required = false) String deviceId) {
        Started s = open.remove(id);
        if (s == null || !java.util.Objects.equals(s.student(), StudentContext.currentOrDefault())) {
            return ResponseEntity.status(404).body(Map.of("error", "okänd övning"));
        }
        Duration elapsed = Duration.between(s.at(), Instant.now());
        long active = body.activeSeconds() == null ? elapsed.toSeconds() : Math.max(0, body.activeSeconds());
        Duration time = Duration.ofSeconds(Math.min(Math.min(active, elapsed.toSeconds()), MAX.toSeconds()));
        boolean counted = sessions.recordPractice(deviceId, "Övning: " + s.what(), time).isPresent();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("minutes", time.toMinutes());
        out.put("counted", counted);
        if (s.homeworkId() != null && time.toSeconds() >= 120) {
            planning.today().stream()
                    .filter(t -> !t.item().isDone() && s.homeworkId().equals(t.item().getHomeworkId()))
                    .findFirst()
                    .ifPresent(t -> out.put("planItem", Map.of("id", t.item().getId(), "task", t.item().getTask())));
        }
        return ResponseEntity.ok(out);
    }
}
