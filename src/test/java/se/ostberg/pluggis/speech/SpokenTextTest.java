package se.ostberg.pluggis.speech;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SpokenTextTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 22);

    private static String say(String s) {
        return SpokenText.forSpeech(s, TODAY);
    }

    @Test
    void datesBecomeWords() {
        assertThat(say("Provet är 2026-11-24.")).isEqualTo("Provet är 24 november.");
        assertThat(say("Inlämning 2027-01-08")).isEqualTo("Inlämning 8 januari 2027");
        assertThat(say("Provet är på måndag 24/11.")).isEqualTo("Provet är på måndag 24 november.");
        assertThat(say("Glosförhör fredag 3/4")).isEqualTo("Glosförhör fredag 3 april");
        assertThat(say("Lämna in senast 1/12")).isEqualTo("Lämna in senast 1 december");
    }

    @Test
    void fractionsStayFractions() {
        assertThat(say("Räkna ut 3/4 av 20.")).isEqualTo("Räkna ut 3/4 av 20.");
        assertThat(say("Vad är 1/2 + 1/4?")).isEqualTo("Vad är 1/2 + 1/4?");
        assertThat(say("Talet 3.5 är större än 3.")).isEqualTo("Talet 3.5 är större än 3.");
    }

    @Test
    void weeksPagesTimesAndAbbreviations() {
        assertThat(say("Läxa v.48: Utkik s. 40–72, kap. 4")).isEqualTo("Läxa vecka 48: Utkik sidorna 40 till 72, kapitel 4");
        assertThat(say("Läs s. 12")).isEqualTo("Läs sidan 12");
        assertThat(say("Vi börjar kl. 16:30")).isEqualTo("Vi börjar klockan 16 och 30");
        assertThat(say("Klart 18:00")).isEqualTo("Klart klockan 18");
        assertThat(say("Ta t.ex. en paus, bl.a. för att dricka vatten osv.")).isEqualTo(
                "Ta till exempel en paus, bland annat för att dricka vatten och så vidare");
        assertThat(say("**Bra jobbat!** 🎉")).isEqualTo("Bra jobbat!");
    }
}
