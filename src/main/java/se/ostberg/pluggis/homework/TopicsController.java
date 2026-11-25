package se.ostberg.pluggis.homework;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.ostberg.pluggis.claude.ClaudeClient;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.support.JsonExtract;
import se.ostberg.pluggis.support.Prompts;

/**
 * Delområden att öva på i en läxa (t.ex. "Siffror 1–100", "Veckodagar") – knappar när eleven väljer övning.
 * Tas normalt fram när materialet läses in; för äldre läxor räknas de fram här första gången och sparas.
 */
@RestController
@RequestMapping("/api/homework")
public class TopicsController {

    private static final Logger log = LoggerFactory.getLogger(TopicsController.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Generated(List<String> topics) {
    }

    private final HomeworkService homework;
    private final MaterialService materials;
    private final ClaudeClient claude;
    private final Prompts prompts;
    private final PluggisProperties props;
    private final ObjectMapper mapper;

    public TopicsController(HomeworkService homework, MaterialService materials, ClaudeClient claude, Prompts prompts,
                            PluggisProperties props, ObjectMapper mapper) {
        this.homework = homework;
        this.materials = materials;
        this.claude = claude;
        this.prompts = prompts;
        this.props = props;
        this.mapper = mapper;
    }

    /** {@code refresh=true}: räkna fram dem igen (t.ex. efter nytt material). */
    @GetMapping("/{id}/topics")
    public ResponseEntity<?> topics(@PathVariable long id, @RequestParam(defaultValue = "false") boolean refresh) {
        Homework h = homework.get(id).orElse(null);
        if (h == null) {
            return ResponseEntity.notFound().build();
        }
        if (!h.getTopics().isEmpty() && !refresh) {
            return ResponseEntity.ok(Map.of("topics", h.getTopics()));
        }
        String mat = materials.allText(id);
        boolean hasContent = (mat != null && !mat.isBlank()) || (h.getDescription() != null && !h.getDescription().isBlank())
                || !h.getSteps().isEmpty();
        if (!hasContent) {
            return ResponseEntity.ok(Map.of("topics", List.of()));
        }
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("title", h.getTitle());
        task.put("subject", h.getSubject());
        task.put("content", h.getDescription());
        task.put("steps", h.getSteps());
        task.put("material", mat == null ? "" : mat.length() > 12000 ? mat.substring(0, 12000) : mat);
        try {
            ObjectNode req = claude.newRequest(props.anthropic().flashcards(), prompts.topics());
            ObjectNode msg = req.putArray("messages").addObject();
            msg.put("role", "user");
            msg.put("content", mapper.writeValueAsString(task));
            Generated g = JsonExtract.parse(claude.create(req, "topics").text(), mapper, Generated.class);
            List<String> topics = g == null || g.topics() == null ? List.of()
                    : new LinkedHashSet<>(g.topics().stream().filter(t -> t != null && !t.isBlank()).map(String::trim)
                    .filter(t -> t.length() <= 40).toList()).stream().limit(8).toList();
            if (!topics.isEmpty()) {
                homework.setTopics(id, topics);
            }
            return ResponseEntity.ok(Map.of("topics", topics));
        } catch (Exception e) {
            log.warn("Delområden kunde inte tas fram: {}", e.toString());
            return ResponseEntity.ok(Map.of("topics", List.of()));
        }
    }
}
