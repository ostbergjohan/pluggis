package se.ostberg.pluggis.flashcards;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import se.ostberg.pluggis.claude.ClaudeClient;
import se.ostberg.pluggis.claude.ClaudeResponse;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.support.JsonExtract;
import se.ostberg.pluggis.support.Prompts;
import se.ostberg.pluggis.support.UploadedDocument;

import java.util.Base64;
import java.util.Set;

/**
 * Låter Claude skapa flashkort från ett ämne, en text (anteckningar, läxbeskrivning)
 * eller ett foto (glosor, boksida, anteckningar).
 */
@Service
public class FlashcardGenerator {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FlashcardGenerator.class);

    static final int MIN_CARDS = 3;
    static final int MAX_CARDS = 40;
    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final ClaudeClient claude;
    private final FlashcardService flashcards;
    private final Prompts prompts;
    private final PluggisProperties props;
    private final ObjectMapper mapper;

    public FlashcardGenerator(ClaudeClient claude, FlashcardService flashcards, Prompts prompts,
                              PluggisProperties props, ObjectMapper mapper) {
        this.claude = claude;
        this.flashcards = flashcards;
        this.prompts = prompts;
        this.props = props;
        this.mapper = mapper;
    }

    public record Result(Deck deck, int cardCount, String note) {
    }

    /** Från ämne och/eller källtext, t.ex. "Glosor kapitel 3, engelska" eller elevens anteckningar. */
    public Result fromText(String topic, String sourceText, String subject, int count, Long homeworkId) {
        StringBuilder ask = new StringBuilder("Skapa ").append(clamp(count)).append(" flashkort.");
        if (subject != null && !subject.isBlank()) {
            ask.append(" Ämne: ").append(subject).append('.');
        }
        if (topic != null && !topic.isBlank()) {
            ask.append(" Område: ").append(topic).append('.');
        }
        if (sourceText != null && !sourceText.isBlank()) {
            ask.append("\n\nUtgå ENDAST från detta material:\n").append(sourceText);
        }
        ObjectNode req = request(props.anthropic().flashcards());
        ObjectNode msg = req.putArray("messages").addObject();
        msg.put("role", "user");
        msg.put("content", ask.toString());
        return save(claude.create(req, "flashcards"), subject, homeworkId, Deck.Source.GENERATED);
    }

    /** Från ett foto: glosor, en boksida eller anteckningar. */
    public Result fromImage(byte[] image, String mediaType, String hint, int count) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("Ingen bild");
        }
        if (image.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Bilden är för stor (max 5 MB)");
        }
        if (mediaType == null || !MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("Bildformatet stöds inte: " + mediaType);
        }
        ObjectNode req = request(props.anthropic().vision());
        ObjectNode msg = req.putArray("messages").addObject();
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        ObjectNode img = content.addObject();
        img.put("type", "image");
        ObjectNode src = img.putObject("source");
        src.put("type", "base64");
        src.put("media_type", mediaType);
        src.put("data", Base64.getEncoder().encodeToString(image));
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", "Skapa upp till " + clamp(count) + " flashkort av det som står på bilden."
                + (hint == null || hint.isBlank() ? "" : " Eleven säger: " + hint));
        return save(claude.create(req, "flashcards-vision"), null, null, Deck.Source.PHOTO);
    }

    /** Från en fil: bild, PDF, Word eller text (glosor, anteckningar, en sida ur boken). */
    public Result fromDocument(UploadedDocument doc, String hint, int count) {
        ObjectNode req = request(doc.kind() == UploadedDocument.Kind.IMAGE || doc.kind() == UploadedDocument.Kind.PDF
                ? props.anthropic().vision() : props.anthropic().flashcards());
        ObjectNode msg = req.putArray("messages").addObject();
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        doc.addTo(content, mapper);
        content.addObject().put("type", "text").put("text", "Skapa upp till " + clamp(count) + " flashkort av materialet."
                + (hint == null || hint.isBlank() ? "" : " Eleven säger: " + hint));
        return save(claude.create(req, "flashcards-document"), null, null,
                doc.kind() == UploadedDocument.Kind.IMAGE ? Deck.Source.PHOTO : Deck.Source.GENERATED);
    }

    private ObjectNode request(PluggisProperties.ModelProfile profile) {
        return claude.newRequest(profile, prompts.flashcards());
    }

    private Result save(ClaudeResponse response, String subjectFallback, Long homeworkId, Deck.Source source) {
        GeneratedDeck generated = withoutLeaks(JsonExtract.parse(response.text(), mapper, GeneratedDeck.class));
        var newCards = generated.toNewCards();
        if (newCards.isEmpty()) {
            throw new IllegalArgumentException(generated.note() == null
                    ? "Inga kort kunde skapas" : generated.note());
        }
        String name = generated.deckName() == null || generated.deckName().isBlank() ? "Ny kortlek" : generated.deckName();
        String subject = generated.subject() == null ? subjectFallback : generated.subject();
        Deck deck = flashcards.createDeck(name, subject, homeworkId, source, newCards);
        return new Result(deck, flashcards.cards(deck.getId()).size(), generated.note());
    }

    /** Svar till omskrivningen: samma ordning som korten vi skickade. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Rewritten(java.util.List<GeneratedDeck.GeneratedCard> cards) {
    }

    /**
     * Kort där frågan avslöjar svaret skrivs om av Claude (en gång). Avslöjar de det fortfarande tas de bort.
     * Ledtrådar som innehåller svaret tas bort.
     */
    GeneratedDeck withoutLeaks(GeneratedDeck deck) {
        if (deck.cards() == null || deck.cards().isEmpty()) {
            return deck;
        }
        java.util.List<GeneratedDeck.GeneratedCard> ok = new java.util.ArrayList<>();
        java.util.List<GeneratedDeck.GeneratedCard> bad = new java.util.ArrayList<>();
        for (GeneratedDeck.GeneratedCard c : deck.cards()) {
            GeneratedDeck.GeneratedCard clean = CardCheck.leaks(c.hint(), c.back())
                    ? new GeneratedDeck.GeneratedCard(c.front(), c.back(), null) : c;
            (CardCheck.leaks(clean.front(), clean.back()) ? bad : ok).add(clean);
        }
        if (!bad.isEmpty()) {
            log.info("Flashkort: {} av {} frågor avslöjade svaret – skriver om", bad.size(), deck.cards().size());
            for (GeneratedDeck.GeneratedCard fixed : rewrite(bad)) {
                if (fixed != null && fixed.front() != null && !CardCheck.leaks(fixed.front(), fixed.back())) {
                    ok.add(CardCheck.leaks(fixed.hint(), fixed.back())
                            ? new GeneratedDeck.GeneratedCard(fixed.front(), fixed.back(), null) : fixed);
                }
            }
        }
        return new GeneratedDeck(deck.deckName(), deck.subject(), ok, deck.note());
    }

    private java.util.List<GeneratedDeck.GeneratedCard> rewrite(java.util.List<GeneratedDeck.GeneratedCard> bad) {
        try {
            ObjectNode req = claude.newRequest(props.anthropic().flashcards(),
                    "Du skriver om flashkort där frågan avslöjar svaret. Behåll svaret (back) exakt. Skriv en ny fråga "
                            + "(front) som INTE innehåller svaret, samma ordstam eller en annan böjning av det, och som "
                            + "fortfarande har just det svaret. Ledtråden får inte heller avslöja svaret. "
                            + "Svara ENDAST med JSON: {\"cards\":[{\"front\":\"…\",\"back\":\"…\",\"hint\":\"… eller null\"}]} "
                            + "i samma ordning.");
            ObjectNode msg = req.putArray("messages").addObject();
            msg.put("role", "user");
            msg.put("content", mapper.writeValueAsString(java.util.Map.of("cards", bad)));
            Rewritten r = JsonExtract.parse(claude.create(req, "flashcards-fix").text(), mapper, Rewritten.class);
            return r.cards() == null ? java.util.List.of() : r.cards();
        } catch (Exception e) {
            log.warn("Kunde inte skriva om flashkort: {}", e.toString());
            return java.util.List.of();
        }
    }

    static int clamp(int count) {
        if (count <= 0) {
            return 12;
        }
        return Math.max(MIN_CARDS, Math.min(MAX_CARDS, count));
    }
}
