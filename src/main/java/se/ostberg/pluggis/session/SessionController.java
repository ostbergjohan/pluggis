package se.ostberg.pluggis.session;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** För test med curl och för en framtida föräldravy. */
@RestController
@RequestMapping("/api/devices/{deviceId}/sessions")
public class SessionController {

    public record StartRequest(String task, Integer minutes) {
    }

    public record FinishRequest(String summary) {
    }

    private final StudySessionService sessions;

    public SessionController(StudySessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    public List<StudySession> history(@PathVariable String deviceId) {
        return sessions.history(deviceId);
    }

    @GetMapping("/active")
    public ResponseEntity<StudySession> active(@PathVariable String deviceId) {
        return ResponseEntity.of(sessions.active(deviceId));
    }

    @PostMapping
    public StudySession start(@PathVariable String deviceId, @RequestBody StartRequest body) {
        return sessions.start(deviceId, body.task() == null ? "Läxor" : body.task(),
                body.minutes() == null ? 15 : body.minutes());
    }

    @PostMapping("/active/finish")
    public ResponseEntity<StudySession> finish(@PathVariable String deviceId, @RequestBody(required = false) FinishRequest body) {
        return ResponseEntity.of(sessions.finish(deviceId, body == null ? null : body.summary()));
    }
}
