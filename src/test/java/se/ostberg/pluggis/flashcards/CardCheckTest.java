package se.ostberg.pluggis.flashcards;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardCheckTest {

    @Test
    void catchesQuestionsThatGiveAwayTheAnswer() {
        assertThat(CardCheck.leaks("Vad kallas det gröna färgämnet klorofyll?", "Klorofyll")).isTrue();
        assertThat(CardCheck.leaks("Vad kallas fotosyntesen?", "Fotosyntes")).isTrue();
        assertThat(CardCheck.leaks("Vilken stad är huvudstaden Stockholm i?", "Stockholm")).isTrue();
        assertThat(CardCheck.leaks("Vem var kung Gustav Vasa?", "Gustav Vasa")).isTrue();
        assertThat(CardCheck.leaks("Vad är ledtråden?", "ledtråd")).isTrue();
    }

    @Test
    void acceptsGoodCards() {
        assertThat(CardCheck.leaks("Vad heter det gröna färgämnet i växternas blad?", "Klorofyll")).isFalse();
        assertThat(CardCheck.leaks("Vad kallas processen när växter gör socker av ljus?", "Fotosyntes")).isFalse();
        assertThat(CardCheck.leaks("hund", "dog")).isFalse();
        assertThat(CardCheck.leaks("Vilket år började andra världskriget?", "1939")).isFalse();
        assertThat(CardCheck.leaks("Vad är 7 · 8?", "56")).isFalse();
        assertThat(CardCheck.leaks("Vem grundade Stockholm enligt sägnen?", "Birger jarl")).isFalse();
        // Långt svar där bara ett ord råkar finnas i frågan
        assertThat(CardCheck.leaks("Vad är fotosyntes?", "När växter gör socker av ljus, vatten och koldioxid")).isFalse();
    }
}
