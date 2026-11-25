package se.ostberg.pluggis.exam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** Testprov – från elevchatten. */
@RestController
@RequestMapping("/api/exam")
public class MockExamController {

    private static final Logger log = LoggerFactory.getLogger(MockExamController.class);
    private final MockExamService exams;

    public MockExamController(MockExamService exams) {
        this.exams = exams;
    }

    public record StartRequest(Long homeworkId, Integer count, String focus) {
    }

    /** {@code answers}: frågans nummer → elevens svar (valt alternativ eller skriven text). */
    public record SubmitRequest(Map<String, String> answers) {
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody StartRequest body) {
        try {
            return ResponseEntity.ok(exams.start(body.homeworkId(), body.count() == null ? 10 : body.count(), body.focus()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Testprov kunde inte skapas: {}", e.toString());
            return ResponseEntity.status(503).body(Map.of("error", "Pluggis kunde inte göra ett testprov just nu – prova igen om en stund"));
        }
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<?> submit(@PathVariable String id, @RequestBody SubmitRequest body,
                                    @RequestParam(required = false) String deviceId) {
        Map<Integer, String> answers = new HashMap<>();
        if (body.answers() != null) {
            body.answers().forEach((k, v) -> {
                try {
                    answers.put(Integer.parseInt(k.trim()), v);
                } catch (NumberFormatException ignored) {
                    // okänd nyckel
                }
            });
        }
        return exams.submit(id, answers, deviceId).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404).body(Map.of("error", "Testprovet har gått ut – starta ett nytt")));
    }
}
