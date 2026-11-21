package se.ostberg.pluggis.flashcards;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import se.ostberg.pluggis.support.JsonExtract;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CardTest {

    private final Instant now = Instant.parse("2026-11-21T15:00:00Z");

    @Test
    void correctAnswersMoveCardUpAndFurtherAway() {
        Card card = new Card(1L, "hund", "dog", null, now);
        assertThat(card.isDue(now)).isTrue();

        card.grade(true, now);
        assertThat(card.getBox()).isEqualTo(2);
        assertThat(card.getDueAt()).isEqualTo(now.plus(Duration.ofDays(1)));

        for (int i = 0; i < 10; i++) {
            card.grade(true, now);
        }
        assertThat(card.getBox()).isEqualTo(Card.MAX_BOX);
        assertThat(card.getTimesCorrect()).isEqualTo(11);
    }

    @Test
    void wrongAnswerResetsToBoxOneAndComesBackSoon() {
        Card card = new Card(1L, "katt", "cat", null, now);
        card.grade(true, now);
        card.grade(true, now);

        card.grade(false, now);

        assertThat(card.getBox()).isEqualTo(1);
        assertThat(card.getDueAt()).isEqualTo(now.plus(Card.RETRY_AFTER_WRONG));
        assertThat(card.isDue(now)).isFalse();
        assertThat(card.isDue(now.plus(Duration.ofMinutes(3)))).isTrue();
    }

    @Test
    void parsesGeneratedDeck() {
        String text = """
                ```json
                {"deck_name":"Engelska kap 3","subject":"Engelska",
                 "cards":[{"front":"hund","back":"dog","hint":null},{"front":"","back":"tom"}],"note":null}
                ```""";

        GeneratedDeck deck = JsonExtract.parse(text, new ObjectMapper(), GeneratedDeck.class);

        assertThat(deck.deckName()).isEqualTo("Engelska kap 3");
        assertThat(deck.toNewCards()).hasSize(2); // tomma filtreras bort när de sparas
    }

    @Test
    void cardCountIsClamped() {
        assertThat(FlashcardGenerator.clamp(0)).isEqualTo(12);
        assertThat(FlashcardGenerator.clamp(1)).isEqualTo(FlashcardGenerator.MIN_CARDS);
        assertThat(FlashcardGenerator.clamp(500)).isEqualTo(FlashcardGenerator.MAX_CARDS);
    }
}
