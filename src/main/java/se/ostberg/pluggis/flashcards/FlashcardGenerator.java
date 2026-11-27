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

    /** "quiz" = flerval, "cloze" = lucktext, "mix" = blandat. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private se.ostberg.pluggis.support.Variety variety;

    public Result quiz(String mode, String topic, String sourceText, String subject, int count, Long homeworkId) {
        return quiz(mode, topic, sourceText, subject, count, homeworkId, null);
    }

    /** {@code focus}: ett delområde eleven valt (t.ex. "Siffror 1–100") – bara frågor om det. */
    public Result quiz(String mode, String topic, String sourceText, String subject, int count, Long homeworkId, String focus) {
        boolean focused = focus != null && !focus.isBlank();
        String varietyKey = focused ? "quiz|" + focus.trim() : "quiz";
        String m = mode == null ? "mix" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if ("vocab".equals(m) || "vocab-reverse".equals(m)) {
            return vocab(topic, sourceText, subject, count, homeworkId, focus, "vocab-reverse".equals(m));
        }
        String kinds = switch (m) {
            case "cloze" -> "Bara lucktext (\"cloze\").";
            case "quiz", "choice" -> "Bara flerval (\"choice\").";
            default -> "Blanda flerval (\"choice\") och lucktext (\"cloze\"), ungefär hälften av varje.";
        };
        StringBuilder ask = new StringBuilder("Gör ").append(clamp(count)).append(" frågor. ").append(kinds);
        if (subject != null && !subject.isBlank()) {
            ask.append(" Ämne: ").append(subject).append('.');
        }
        if (topic != null && !topic.isBlank()) {
            ask.append(" Område: ").append(topic).append('.');
        }
        if (focused) {
            ask.append("\n\nFOKUS: eleven vill öva på \"").append(focus.trim())
                    .append("\". Gör BARA frågor om det delområdet ur materialet.");
        }
        if (variety != null) {
            ask.append("\n\n").append(variety.focusHint());
            java.util.List<String> avoid = variety.recent(varietyKey, homeworkId);
            if (!avoid.isEmpty()) {
                ask.append("\nDe här frågorna fick eleven nyss – gör andra:\n- ").append(String.join("\n- ", avoid));
            }
        }
        if (sourceText != null && !sourceText.isBlank()) {
            ask.append("\n\nUtgå ENDAST från detta material:\n").append(sourceText);
        } else {
            ask.append("\n\nInget material finns – håll dig till grunderna i området på årskurs 8-nivå "
                    + "och skriv det i note.");
        }
        ObjectNode req = claude.newRequest(props.anthropic().flashcards(), prompts.quiz());
        ObjectNode msg = req.putArray("messages").addObject();
        msg.put("role", "user");
        msg.put("content", ask.toString());
        GeneratedDeck generated = JsonExtract.parse(claude.create(req, "quiz").text(), mapper, GeneratedDeck.class);
        java.util.List<FlashcardService.NewCard> cards = quizCards(generated);
        if (cards.isEmpty()) {
            throw new IllegalArgumentException(generated.note() == null ? "Inga frågor kunde skapas" : generated.note());
        }
        String base = generated.deckName() == null || generated.deckName().isBlank()
                ? (topic == null || topic.isBlank() ? "Quiz" : topic) : generated.deckName();
        String name = ("cloze".equals(m) ? "✏️ Lucktext: " : "⚡ Snabbquiz: ") + base;
        String subj = generated.subject() == null ? subject : generated.subject();
        Deck deck = flashcards.createDeck(name, subj, homeworkId, Deck.Source.GENERATED, cards);
        java.util.List<Card> made = flashcards.cards(deck.getId());
        if (variety != null) {
            variety.remember(varietyKey, homeworkId, made.stream().map(Card::getFront).limit(12).toList());
        }
        return new Result(deck, made.size(), generated.note());
    }

    /**
     * Muntligt glosförhör: korten har ordet på målspråket fram och den svenska betydelsen bak (flera svar
     * skilda med " / "). Tab5 säger ordet och eleven svarar på svenska, som rösttjänsten förstår.
     */
    Result vocab(String topic, String sourceText, String subject, int count, Long homeworkId, String focus, boolean reverse) {
        StringBuilder ask = new StringBuilder("Gör ").append(clamp(count)).append(" glosor.");
        if (reverse) {
            ask.append(" VÄND PÅ RIKTNINGEN: \"front\" är ordet på elevens eget språk och \"back\" är ordet på "
                    + "målspråket (flera rimliga svar skilda med \" / \"). Eleven svarar högt på målspråket.");
        }
        if (subject != null && !subject.isBlank()) {
            ask.append(" Ämne: ").append(subject).append('.');
        }
        if (topic != null && !topic.isBlank()) {
            ask.append(" Område: ").append(topic).append('.');
        }
        if (focus != null && !focus.isBlank()) {
            ask.append("\n\nFOKUS: bara glosor om \"").append(focus.trim()).append("\".");
        }
        String varietyKey = (reverse ? "vocab-reverse" : "vocab") + (focus == null || focus.isBlank() ? "" : "|" + focus.trim());
        if (variety != null) {
            java.util.List<String> avoid = variety.recent(varietyKey, homeworkId);
            if (!avoid.isEmpty()) {
                ask.append("\nDe här fick eleven nyss – ta andra om det finns fler:\n- ").append(String.join("\n- ", avoid));
            }
        }
        if (sourceText != null && !sourceText.isBlank()) {
            ask.append("\n\nGlosorna finns i detta material:\n").append(sourceText);
        } else {
            throw new IllegalArgumentException("Läxan har inga glosor än – lägg till material (foto på glosorna) först");
        }
        ObjectNode req = claude.newRequest(props.anthropic().flashcards(), prompts.vocab());
        ObjectNode msg = req.putArray("messages").addObject();
        msg.put("role", "user");
        msg.put("content", ask.toString());
        GeneratedDeck generated = JsonExtract.parse(claude.create(req, "vocab").text(), mapper, GeneratedDeck.class);
        java.util.List<FlashcardService.NewCard> cards = vocabCards(generated);
        if (cards.isEmpty()) {
            throw new IllegalArgumentException(generated.note() == null ? "Hittade inga glosor" : generated.note());
        }
        String base = generated.deckName() == null || generated.deckName().isBlank()
                ? (topic == null || topic.isBlank() ? "Glosor" : topic) : generated.deckName();
        String subj = generated.subject() == null ? subject : generated.subject();
        Deck deck = flashcards.createDeck("🗣️ Glosförhör: " + base, subj, homeworkId, Deck.Source.GENERATED, cards);
        java.util.List<Card> made = flashcards.cards(deck.getId());
        if (variety != null) {
            variety.remember(varietyKey, homeworkId, made.stream().map(Card::getFront).limit(12).toList());
        }
        return new Result(deck, made.size(), generated.note());
    }

    /** Glosor: ord fram, kort svensk betydelse bak (högst 60 tecken), inga dubbletter. */
    static java.util.List<FlashcardService.NewCard> vocabCards(GeneratedDeck g) {
        java.util.List<FlashcardService.NewCard> out = new java.util.ArrayList<>();
        if (g == null || g.cards() == null) {
            return out;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (GeneratedDeck.GeneratedCard c : g.cards()) {
            if (c == null || c.front() == null || c.back() == null) {
                continue;
            }
            String front = c.front().trim(), back = c.back().trim();
            if (front.isEmpty() || back.isEmpty() || back.length() > 60 || front.equalsIgnoreCase(back)
                    || !seen.add(front.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            out.add(new FlashcardService.NewCard(front, back, null));
        }
        return out;
    }

    /** Släpp bara igenom frågor som går att spela: minst två felaktiga alternativ, inget avslöjat svar, lucka i lucktext. */
    static java.util.List<FlashcardService.NewCard> quizCards(GeneratedDeck g) {
        java.util.List<FlashcardService.NewCard> out = new java.util.ArrayList<>();
        if (g == null || g.cards() == null) {
            return out;
        }
        for (GeneratedDeck.GeneratedCard c : g.cards()) {
            if (c == null || c.front() == null || c.back() == null || c.front().isBlank() || c.back().isBlank()) {
                continue;
            }
            String type = "cloze".equalsIgnoreCase(c.type()) ? "CLOZE" : "CHOICE";
            String front = c.front().trim().replaceAll("_{2,}|\\[\\s*\\.{3}\\s*\\]|…{1,}", "___");
            String back = c.back().trim();
            if (type.equals("CLOZE") && (!front.contains("___") || front.indexOf("___") != front.lastIndexOf("___"))) {
                continue;
            }
            if (CardCheck.leaks(front.replace("___", " "), back)) {
                continue;
            }
            java.util.LinkedHashSet<String> wrong = new java.util.LinkedHashSet<>();
            if (c.options() != null) {
                for (String o : c.options()) {
                    if (o != null && !o.isBlank() && !o.trim().equalsIgnoreCase(back)) {
                        wrong.add(o.trim());
                    }
                }
            }
            if (wrong.size() < 2) {
                continue;
            }
            String hint = c.hint() == null || c.hint().isBlank() || CardCheck.leaks(c.hint(), back) ? null : c.hint().trim();
            out.add(new FlashcardService.NewCard(front, back, hint, type, new java.util.ArrayList<>(wrong).subList(0, Math.min(3, wrong.size()))));
        }
        return out;
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
