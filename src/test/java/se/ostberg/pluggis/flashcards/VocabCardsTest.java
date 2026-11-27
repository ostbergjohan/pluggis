package se.ostberg.pluggis.flashcards;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VocabCardsTest {

    private static GeneratedDeck.GeneratedCard c(String front, String back) {
        return new GeneratedDeck.GeneratedCard(front, back, null);
    }

    @Test
    void glosorMedKortSvenskBetydelse() {
        var deck = new GeneratedDeck("Spanska kap 3", "Spanska", List.of(
                c("el perro", "hunden / en hund"),
                c("El perro", "hund"),                         // dubblett
                c("tener hambre", "vara hungrig"),
                c("casa", "casa"),                             // samma på båda sidor
                c("la biblioteca", "biblioteket, där man lånar böcker och läser i lugn och ro hela dagen"),   // för lång
                c(" ", "tom")),
                null);
        var out = FlashcardGenerator.vocabCards(deck);
        assertThat(out).extracting(FlashcardService.NewCard::front).containsExactly("el perro", "tener hambre");
        assertThat(out.get(0).back()).isEqualTo("hunden / en hund");
        assertThat(out.get(0).kind()).isNull();
    }
}
