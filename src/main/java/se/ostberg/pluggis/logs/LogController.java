package se.ostberg.pluggis.logs;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Backendens logg för webbgränssnittets Logg-flik. Skyddas av token som resten av /api. */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogBuffer buffer;

    public LogController(LogBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * Hämta nya rader: anropa med {@code after} = senaste {@code last} du fått, så kommer bara nya.
     */
    @GetMapping
    public Map<String, Object> lines(@RequestParam(defaultValue = "0") long after,
                                     @RequestParam(defaultValue = "INFO") String level,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(defaultValue = "500") int limit) {
        List<LogBuffer.LogLine> lines = buffer.since(after, level, q, limit);
        return Map.of("lines", lines, "last", buffer.lastSeq());
    }
}
