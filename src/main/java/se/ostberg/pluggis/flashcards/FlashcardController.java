package se.ostberg.pluggis.flashcards;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.planning.PlanningService;
import se.ostberg.pluggis.support.UploadedDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/flashcards")
public class FlashcardController {

    /** Publiceras när en lek skapats från foto eller generering, så enheten kan säga till. */
    public record DeckCreated(String deviceId, Deck deck, int cardCount) {
    }

    public record NewDeck(@NotBlank String name, String subject, Long homeworkId,
                          @NotEmpty List<FlashcardService.NewCard> cards) {
    }

    public record GenerateRequest(String topic, String sourceText, String subject, Integer count, Long homeworkId) {
    }

    /** {@code mode}: quiz (flerval), cloze (lucktext) eller mix. */
    public record QuizRequest(Long homeworkId, String mode, Integer count, String topic, String sourceText, String subject) {
    }

    public record GradeRequest(boolean correct) {
    }

    private final FlashcardService flashcards;
    private final FlashcardGenerator generator;
    private final ApplicationEventPublisher events;
    private final HomeworkService homework;
    private final PlanningService planning;

    public FlashcardController(FlashcardService flashcards, FlashcardGenerator generator, ApplicationEventPublisher events,
                               HomeworkService homework, PlanningService planning) {
        this.flashcards = flashcards;
        this.generator = generator;
        this.events = events;
        this.homework = homework;
        this.planning = planning;
    }

    private se.ostberg.pluggis.homework.MaterialService materials;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMaterials(se.ostberg.pluggis.homework.MaterialService materials) {
        this.materials = materials;
    }

    /**
     * Snabbquiz eller lucktext från en läxas innehåll och material (eller fritt ämne/text).
     * Eleven får också göra det från chatten (bara för sina egna läxor).
     */
    @PostMapping("/quiz")
    public ResponseEntity<?> quiz(@RequestBody QuizRequest body) {
        String topic = body.topic();
        String subject = body.subject();
        StringBuilder source = new StringBuilder(body.sourceText() == null ? "" : body.sourceText());
        if (body.homeworkId() != null) {
            var h = homework.get(body.homeworkId()).orElse(null);
            if (h == null) {
                return ResponseEntity.notFound().build();
            }
            topic = topic == null ? h.getTitle() : topic;
            subject = subject == null ? h.getSubject() : subject;
            if (h.getDescription() != null && !h.getDescription().isBlank()) {
                source.append(source.isEmpty() ? "" : "\n\n").append("Det här ingår: ").append(h.getDescription());
            }
            if (h.getSteps() != null && !h.getSteps().isEmpty()) {
                source.append("\n").append(String.join("\n", h.getSteps()));
            }
            String mat = materials == null ? "" : materials.allText(body.homeworkId());
            if (!mat.isBlank()) {
                source.append("\n\nMaterial:\n").append(mat.length() > 20000 ? mat.substring(0, 20000) : mat);
            }
        }
        try {
            return ResponseEntity.ok(generator.quiz(body.mode(), topic, source.toString(), subject,
                    body.count() == null ? 10 : body.count(), body.homeworkId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(java.util.Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(FlashcardController.class).warn("Quiz kunde inte skapas: {}", e.toString());
            return ResponseEntity.status(503).body(java.util.Map.of("error", "Pluggis kunde inte göra frågor just nu – prova igen om en stund"));
        }
    }

    @GetMapping("/decks")
    public List<FlashcardService.DeckStats> decks() {
        return flashcards.decksWithStats();
    }

    @GetMapping("/decks/{deckId}/cards")
    public List<Card> cards(@PathVariable long deckId) {
        return flashcards.cards(deckId);
    }

    /** Egen lek, t.ex. glosor som en förälder skriver in. */
    @PostMapping("/decks")
    @ResponseStatus(HttpStatus.CREATED)
    public Deck create(@Valid @RequestBody NewDeck body) {
        return flashcards.createDeck(body.name(), body.subject(), body.homeworkId(), Deck.Source.MANUAL, body.cards());
    }

    @PostMapping("/decks/{deckId}/cards")
    public List<Card> addCards(@PathVariable long deckId, @RequestBody List<FlashcardService.NewCard> cards) {
        return flashcards.addCards(deckId, cards);
    }

    public record EditCard(String front, String back, String hint) {
    }

    @PutMapping("/cards/{cardId}")
    public ResponseEntity<Card> editCard(@PathVariable long cardId, @RequestBody EditCard body) {
        return ResponseEntity.of(flashcards.updateCard(cardId, body.front(), body.back(), body.hint()));
    }

    @DeleteMapping("/cards/{cardId}")
    public ResponseEntity<Void> deleteCard(@PathVariable long cardId) {
        return flashcards.deleteCard(cardId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/decks/{deckId}")
    public ResponseEntity<Void> deleteDeck(@PathVariable long deckId) {
        return flashcards.deleteDeck(deckId) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Låt Claude skapa en lek från ämne och/eller text. */
    @PostMapping("/decks/{deckId}/reset")
    public Map<String, Integer> resetDeck(@PathVariable long deckId) {
        return Map.of("cards", flashcards.resetDeck(deckId));
    }

    @PostMapping("/generate")
    public FlashcardGenerator.Result generate(@RequestBody GenerateRequest body,
                                              @RequestParam(required = false) String deviceId) {
        FlashcardGenerator.Result r = generator.fromText(body.topic(), body.sourceText(), body.subject(),
                body.count() == null ? 12 : body.count(), body.homeworkId());
        if (body.homeworkId() != null) {
            homework.linkDeck(body.homeworkId(), r.deck().getId());
            try {
                planning.replanIfPlanned(body.homeworkId());
            } catch (IllegalArgumentException ignored) {
                // saknar datum – planeras senare
            }
        }
        notifyDevice(deviceId, r);
        return r;
    }

    /** Foto på glosor, en boksida eller anteckningar → ny lek. */
    @PostMapping(path = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FlashcardGenerator.Result photo(@RequestPart("image") MultipartFile image,
                                           @RequestParam(required = false) String deviceId,
                                           @RequestParam(required = false) String hint,
                                           @RequestParam(defaultValue = "20") int count) throws IOException {
        FlashcardGenerator.Result r = generator.fromDocument(
                UploadedDocument.of(image.getBytes(), image.getOriginalFilename(), image.getContentType()), hint, count);
        notifyDevice(deviceId, r);
        return r;
    }

    /** För test utan enhet. */
    @GetMapping("/decks/{deckId}/next")
    public ResponseEntity<Card> next(@PathVariable long deckId, @RequestParam(defaultValue = "false") boolean practiceAnyway,
                                     @RequestParam(required = false) String deviceId,
                                     @RequestParam(defaultValue = "true") boolean announce) {
        // announce=false: Tab5 övar själv på skärmen – visa inte kortet i chatten/coachen också
        return ResponseEntity.of(flashcards.next(deckId, practiceAnyway, announce ? deviceId : null));
    }

    @PostMapping("/cards/{cardId}/grade")
    public ResponseEntity<Card> grade(@PathVariable long cardId, @RequestBody GradeRequest body,
                                      @RequestParam(required = false) String deviceId,
                                      @RequestParam(defaultValue = "false") boolean quiet) {
        return ResponseEntity.of(flashcards.grade(cardId, body.correct(), deviceId, quiet));
    }

    /** Export till Anki (Arkiv → Importera, fältavgränsare tab). */
    @GetMapping(path = "/decks/{deckId}/export.tsv")
    public ResponseEntity<byte[]> exportAnki(@PathVariable long deckId) {
        StringBuilder sb = new StringBuilder("#separator:tab\n#html:false\n");
        for (Card c : flashcards.cards(deckId)) {
            sb.append(clean(c.getFront())).append('\t').append(clean(c.getBack())).append('\n');
        }
        String name = flashcards.deck(deckId).map(Deck::getName).orElse("kortlek")
                .replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".tsv\"")
                .contentType(new MediaType("text", "tab-separated-values", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private void notifyDevice(String deviceId, FlashcardGenerator.Result r) {
        if (deviceId != null && !deviceId.isBlank()) {
            events.publishEvent(new DeckCreated(deviceId, r.deck(), r.cardCount()));
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').trim();
    }
}
