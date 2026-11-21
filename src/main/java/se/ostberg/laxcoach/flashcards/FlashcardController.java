package se.ostberg.laxcoach.flashcards;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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

    public record GradeRequest(boolean correct) {
    }

    private final FlashcardService flashcards;
    private final FlashcardGenerator generator;
    private final ApplicationEventPublisher events;

    public FlashcardController(FlashcardService flashcards, FlashcardGenerator generator, ApplicationEventPublisher events) {
        this.flashcards = flashcards;
        this.generator = generator;
        this.events = events;
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

    /** Låt Claude skapa en lek från ämne och/eller text. */
    @PostMapping("/generate")
    public FlashcardGenerator.Result generate(@RequestBody GenerateRequest body,
                                              @RequestParam(required = false) String deviceId) {
        FlashcardGenerator.Result r = generator.fromText(body.topic(), body.sourceText(), body.subject(),
                body.count() == null ? 12 : body.count(), body.homeworkId());
        notifyDevice(deviceId, r);
        return r;
    }

    /** Foto på glosor, en boksida eller anteckningar → ny lek. */
    @PostMapping(path = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FlashcardGenerator.Result photo(@RequestPart("image") MultipartFile image,
                                           @RequestParam(required = false) String deviceId,
                                           @RequestParam(required = false) String hint,
                                           @RequestParam(defaultValue = "20") int count) throws IOException {
        FlashcardGenerator.Result r = generator.fromImage(image.getBytes(), image.getContentType(), hint, count);
        notifyDevice(deviceId, r);
        return r;
    }

    /** För test utan enhet. */
    @GetMapping("/decks/{deckId}/next")
    public ResponseEntity<Card> next(@PathVariable long deckId, @RequestParam(defaultValue = "false") boolean practiceAnyway,
                                     @RequestParam(required = false) String deviceId) {
        return ResponseEntity.of(flashcards.next(deckId, practiceAnyway, deviceId));
    }

    @PostMapping("/cards/{cardId}/grade")
    public ResponseEntity<Card> grade(@PathVariable long cardId, @RequestBody GradeRequest body,
                                      @RequestParam(required = false) String deviceId) {
        return ResponseEntity.of(flashcards.grade(cardId, body.correct(), deviceId));
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
