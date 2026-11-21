package se.ostberg.laxcoach.planning;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.ostberg.laxcoach.homework.Homework;
import se.ostberg.laxcoach.homework.HomeworkService;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plan")
public class PlanningController {

    /** Fyll i vad som ingår – planen görs om direkt. */
    public record ContentRequest(String content, List<String> steps, Homework.Kind kind, LocalDate dueDate) {
    }

    private final PlanningService planning;
    private final HomeworkService homework;

    public PlanningController(PlanningService planning, HomeworkService homework) {
        this.planning = planning;
        this.homework = homework;
    }

    @GetMapping("/today")
    public List<PlanningService.TodayItem> today() {
        return planning.today();
    }

    @GetMapping("/upcoming")
    public List<PlanItem> upcoming(@RequestParam(defaultValue = "7") int days) {
        return planning.upcoming(days);
    }

    @GetMapping("/homework/{homeworkId}")
    public List<PlanItem> forHomework(@PathVariable long homeworkId) {
        return planning.forHomework(homeworkId);
    }

    @PostMapping("/homework/{homeworkId}/content")
    public ResponseEntity<PlanningService.PlanResult> content(@PathVariable long homeworkId, @RequestBody ContentRequest body) {
        if (homework.updateContent(homeworkId, body.content(), body.steps(), body.kind(), body.dueDate()).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(planning.plan(homeworkId));
    }

    @PostMapping("/homework/{homeworkId}")
    public PlanningService.PlanResult replan(@PathVariable long homeworkId) {
        return planning.plan(homeworkId);
    }

    @PostMapping("/items/{itemId}/done")
    public ResponseEntity<PlanItem> done(@PathVariable long itemId, @RequestParam(required = false) String deviceId) {
        return ResponseEntity.of(planning.complete(itemId, deviceId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
