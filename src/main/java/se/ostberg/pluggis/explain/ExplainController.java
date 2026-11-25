package se.ostberg.pluggis.explain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Förklara för Pluggis – används från elevchatten. */
@RestController
@RequestMapping("/api/explain")
public class ExplainController {

    private static final Logger log = LoggerFactory.getLogger(ExplainController.class);
    private final ExplainService explain;

    public ExplainController(ExplainService explain) {
        this.explain = explain;
    }

    public record StartRequest(Long homeworkId, String focus) {
    }

    public record AnswerRequest(String text) {
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody StartRequest body) {
        return run(() -> ResponseEntity.ok(explain.start(body.homeworkId(), body.focus())));
    }

    @PostMapping("/{id}/answer")
    public ResponseEntity<?> answer(@PathVariable String id, @RequestBody AnswerRequest body,
                                    @RequestParam(required = false) String deviceId) {
        if (body.text() == null || body.text().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Skriv eller säg något först"));
        }
        return run(() -> explain.answer(id, body.text(), deviceId).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404).body(Map.of("error", "Rundan har gått ut – starta en ny"))));
    }

    private ResponseEntity<?> run(java.util.function.Supplier<ResponseEntity<?>> work) {
        try {
            return work.get();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Förklara för Pluggis: {}", e.toString());
            return ResponseEntity.status(503).body(Map.of("error", "Pluggis kan inte lyssna just nu – prova igen om en stund"));
        }
    }
}
