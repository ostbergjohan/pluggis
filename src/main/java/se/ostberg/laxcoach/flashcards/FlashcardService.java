package se.ostberg.laxcoach.flashcards;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class FlashcardService {

    public record NewCard(String front, String back, String hint) {
    }

    public record DeckStats(Deck deck, long cards, long dueNow, long learned) {
    }

    /** Ett kort visas för eleven – enheten visar framsidan på skärmen. */
    public record CardPresented(String deviceId, Card card) {
    }

    /** Ett kort har rättats – enheten visar baksidan och om det var rätt. */
    public record CardGraded(String deviceId, Card card, boolean correct) {
    }

    private final DeckRepository decks;
    private final CardRepository cards;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public FlashcardService(DeckRepository decks, CardRepository cards, ApplicationEventPublisher events) {
        this(decks, cards, events, Clock.systemUTC());
    }

    FlashcardService(DeckRepository decks, CardRepository cards, ApplicationEventPublisher events, Clock clock) {
        this.decks = decks;
        this.cards = cards;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Deck createDeck(String name, String subject, Long homeworkId, Deck.Source source, List<NewCard> newCards) {
        Deck deck = decks.save(new Deck(name, subject, homeworkId, source));
        addCards(deck.getId(), newCards);
        return deck;
    }

    @Transactional
    public List<Card> addCards(long deckId, List<NewCard> newCards) {
        Instant now = clock.instant();
        return newCards.stream()
                .filter(c -> notBlank(c.front()) && notBlank(c.back()))
                .map(c -> cards.save(new Card(deckId, c.front().trim(), c.back().trim(), c.hint(), now)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DeckStats> decksWithStats() {
        Instant now = clock.instant();
        return decks.findAllByOrderByCreatedAtDesc().stream()
                .map(d -> new DeckStats(d,
                        cards.countByDeckId(d.getId()),
                        cards.countByDeckIdAndDueAtLessThanEqual(d.getId(), now),
                        cards.countByDeckIdAndBoxGreaterThanEqual(d.getId(), 4)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<Deck> deck(long deckId) {
        return decks.findById(deckId);
    }

    @Transactional(readOnly = true)
    public List<Card> cards(long deckId) {
        return cards.findByDeckIdOrderByIdAsc(deckId);
    }

    /**
     * Nästa kort att förhöra. Förfallna kort först (lägst fack först).
     * Med {@code practiceAnyway} tas det kort som ligger närmast i tid om inget är förfallet.
     */
    @Transactional(readOnly = true)
    public Optional<Card> next(long deckId, boolean practiceAnyway, String deviceId) {
        Optional<Card> card = cards.findFirstByDeckIdAndDueAtLessThanEqualOrderByBoxAscDueAtAsc(deckId, clock.instant());
        if (card.isEmpty() && practiceAnyway) {
            card = cards.findFirstByDeckIdOrderByDueAtAsc(deckId);
        }
        if (deviceId != null) {
            card.ifPresent(c -> events.publishEvent(new CardPresented(deviceId, c)));
        }
        return card;
    }

    @Transactional
    public Optional<Card> grade(long cardId, boolean correct, String deviceId) {
        return cards.findById(cardId).map(c -> {
            c.grade(correct, clock.instant());
            if (deviceId != null) {
                events.publishEvent(new CardGraded(deviceId, c, correct));
            }
            return c;
        });
    }

    @Transactional(readOnly = true)
    public String summaryForPrompt() {
        List<DeckStats> stats = decksWithStats();
        if (stats.isEmpty()) {
            return "Inga kortlekar än.";
        }
        StringBuilder sb = new StringBuilder();
        stats.stream().limit(10).forEach(s -> sb.append("- [lek ").append(s.deck().getId()).append("] ")
                .append(s.deck().getName())
                .append(" – ").append(s.cards()).append(" kort, ")
                .append(s.dueNow()).append(" att repetera nu, ")
                .append(s.learned()).append(" sitter\n"));
        return sb.toString().trim();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
