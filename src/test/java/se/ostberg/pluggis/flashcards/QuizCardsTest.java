package se.ostberg.pluggis.flashcards;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuizCardsTest {

    private static GeneratedDeck.GeneratedCard c(String type, String front, String back, String... wrong) {
        return new GeneratedDeck.GeneratedCard(front, back, null, type, List.of(wrong));
    }

    @Test
    void släpperIgenomBraFrågorOchStädarResten() {
        var deck = new GeneratedDeck("Fotosyntes", "NO", List.of(
                c("choice", "Vad behöver växter för att göra socker?", "Ljus, vatten och koldioxid", "Jord och salt", "Mörker", "Kväve"),
                c("cloze", "Det gröna färgämnet ____ fångar ljus.", "klorofyll", "glukos", "stärkelse", "klorofyll"),
                c("cloze", "Ingen lucka här alls.", "klorofyll", "a", "b", "c"),             // saknar lucka
                c("choice", "Vad gör klorofyll?", "Fångar ljus", "Fångar ljus"),             // för få fel alternativ
                c("choice", "Vad kallas fotosyntesen?", "Fotosyntes", "Andning", "Jäsning"), // avslöjar svaret
                c("cloze", "___ och ___ behövs.", "Ljus", "a", "b", "c")),                   // två luckor
                null);
        var out = FlashcardGenerator.quizCards(deck);
        assertThat(out).hasSize(2);
        assertThat(out.get(0).kind()).isEqualTo("CHOICE");
        assertThat(out.get(0).wrong()).hasSize(3);
        assertThat(out.get(1).kind()).isEqualTo("CLOZE");
        assertThat(out.get(1).front()).isEqualTo("Det gröna färgämnet ___ fångar ljus.");
        assertThat(out.get(1).wrong()).containsExactly("glukos", "stärkelse"); // rätt svar bortplockat ur fel-listan
    }
}
