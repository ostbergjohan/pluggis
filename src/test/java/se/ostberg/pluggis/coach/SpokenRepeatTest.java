package se.ostberg.pluggis.coach;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpokenRepeatTest {

    @Test
    void kännerIgenUpprepning() {
        assertThat(SpokenRepeat.repeats("Jag sparar NO-provet till fredag.", "NO-provet till fredag, jag sparar.")).isTrue();
        assertThat(SpokenRepeat.repeats("Jag sparar NO-provet till fredag.", "Klart! Vill du börja med sidan 40 nu?")).isFalse();
        assertThat(SpokenRepeat.repeats("", "Hej")).isFalse();
    }
}
