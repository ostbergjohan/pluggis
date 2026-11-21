package se.ostberg.pluggis.homework;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import se.ostberg.pluggis.planning.PlanningService;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/homework")
public class HomeworkController {

    public record NewHomework(String subject, @NotBlank String title, String description,
                              LocalDate dueDate, List<String> steps) {
    }

    private final HomeworkService homework;
    private final PhotoIntakeService photos;
    private final PlanningService planning;

    public HomeworkController(HomeworkService homework, PhotoIntakeService photos, PlanningService planning) {
        this.homework = homework;
        this.photos = photos;
        this.planning = planning;
    }

    @GetMapping
    public List<Homework> list(@RequestParam(defaultValue = "true") boolean openOnly) {
        return openOnly ? homework.open() : homework.all();
    }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Homework create(@Valid @RequestBody NewHomework body) {
        return homework.add(body.subject(), body.title(), body.description(), body.dueDate(),
                body.steps(), Homework.Source.MANUAL);
    }

    public record EditHomework(String subject, String title, String description, List<String> steps,
                               Homework.Kind kind, LocalDate dueDate, Boolean open, Boolean replan) {
    }

    @PutMapping("/{id}")
    public ResponseEntity<Homework> edit(@PathVariable long id, @RequestBody EditHomework body) {
        var updated = homework.edit(id, body.subject(), body.title(), body.description(), body.steps(),
                body.kind(), body.dueDate(), body.open());
        if (updated.isPresent() && !Boolean.FALSE.equals(body.replan())
                && (body.description() != null || body.steps() != null || body.dueDate() != null)) {
            try {
                planning.replanIfPlanned(id);
            } catch (IllegalArgumentException ignored) {
                // t.ex. saknat datum – planen görs när det finns
            }
        }
        return ResponseEntity.of(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        planning.deleteForHomework(id);
        return homework.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/done")
    public ResponseEntity<Homework> done(@PathVariable long id) {
        return ResponseEntity.of(homework.complete(id));
    }

    /**
     * Foto på en uppgift. Används av Tab5 (multipart) och iOS-genvägen
     * ("Hämta innehåll från URL", metod POST, formulär med fältet image).
     */
    @PostMapping(path = "/photo", consumes = "multipart/form-data")
    public PhotoIntakeService.IntakeResult photo(@RequestPart("image") MultipartFile image,
                                                 @RequestParam(required = false) String deviceId,
                                                 @RequestParam(required = false) String hint) throws IOException {
        return photos.intake(image.getBytes(), image.getContentType(), deviceId, hint);
    }

    public record TextIntake(@NotBlank String text) {
    }

    /** Klistra in text från InfoMentors veckoplan, ett veckobrev eller liknande. */
    @PostMapping("/text")
    public PhotoIntakeService.IntakeResult text(@Valid @RequestBody TextIntake body,
                                                @RequestParam(required = false) String deviceId) {
        return photos.intakeText(body.text(), deviceId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
