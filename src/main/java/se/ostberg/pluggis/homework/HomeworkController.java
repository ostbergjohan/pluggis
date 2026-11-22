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
import se.ostberg.pluggis.support.UploadedDocument;

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
    private final MaterialService materials;

    public HomeworkController(HomeworkService homework, PhotoIntakeService photos, PlanningService planning,
                              MaterialService materials) {
        this.homework = homework;
        this.photos = photos;
        this.planning = planning;
        this.materials = materials;
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
                               Homework.Kind kind, LocalDate dueDate, Boolean open, Boolean replan,
                               Boolean clearDueDate) {
    }

    @PutMapping("/{id}")
    public ResponseEntity<Homework> edit(@PathVariable long id, @RequestBody EditHomework body) {
        boolean clear = Boolean.TRUE.equals(body.clearDueDate());
        var updated = homework.edit(id, body.subject(), body.title(), body.description(), body.steps(),
                body.kind(), body.dueDate(), body.open(), clear);
        if (updated.isPresent() && (updated.get().isOther() || updated.get().getDueDate() == null)) {
            planning.deleteForHomework(id); // annat och odaterat planeras inte
            return ResponseEntity.of(updated);
        }
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
        materials.deleteForHomework(id);
        return homework.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** "Städa": markera allt öppet med passerat datum som klart. */
    @PostMapping("/archive-past")
    public Map<String, Integer> archivePast() {
        return Map.of("archived", homework.archivePast());
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
        return photos.intake(UploadedDocument.of(image.getBytes(), image.getOriginalFilename(), image.getContentType()),
                deviceId, hint);
    }

    /**
     * Ladda upp en fil (bild, Word .docx, PDF, text). Utan {@code homeworkId} blir den nya läxor/prov;
     * med {@code homeworkId} blir den material till den läxan (innehåll + ny plan, ev. flashkort).
     */
    @PostMapping(path = "/upload", consumes = "multipart/form-data")
    public Object upload(@RequestParam("file") List<MultipartFile> files,
                         @RequestParam(required = false) Long homeworkId,
                         @RequestParam(defaultValue = "false") boolean flashcards,
                         @RequestParam(required = false) String deviceId,
                         @RequestParam(required = false) String hint) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("Ingen fil");
        }
        if (files.size() > MaterialService.MAX_FILES) {
            throw new IllegalArgumentException("Högst " + MaterialService.MAX_FILES + " filer åt gången");
        }
        List<UploadedDocument> docs = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            docs.add(UploadedDocument.of(f.getBytes(), f.getOriginalFilename(), f.getContentType()));
        }
        if (homeworkId != null) {
            return materials.attach(homeworkId, docs, flashcards);
        }
        return photos.intake(docs, deviceId, hint);
    }

    /** Sidbilden till ett material (förminskad), för att visa den igen. */
    @GetMapping("/material/{materialId}/image")
    public ResponseEntity<byte[]> materialImage(@PathVariable long materialId) {
        return materials.image(materialId)
                .map(m -> ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.parseMediaType(
                                m.type() == null ? "image/jpeg" : m.type()))
                        .header("Cache-Control", "private, max-age=86400")
                        .body(m.data()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/material")
    public List<Material> material(@PathVariable long id) {
        return materials.forHomework(id);
    }

    @DeleteMapping("/material/{materialId}")
    public ResponseEntity<Void> deleteMaterial(@PathVariable long materialId) {
        return materials.delete(materialId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @ExceptionHandler(org.springframework.web.client.RestClientException.class)
    public ResponseEntity<Map<String, String>> claudeDown(org.springframework.web.client.RestClientException e) {
        return ResponseEntity.status(502).body(Map.of("error", "Claude svarar inte just nu – försök igen om en stund"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException e) {
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
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
