package se.ostberg.pluggis.coach;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceSplitterTest {

    private List<String> split(String... chunks) {
        List<String> out = new ArrayList<>();
        SentenceSplitter s = new SentenceSplitter(out::add);
        for (String c : chunks) {
            s.accept(c);
        }
        s.flush();
        return out;
    }

    @Test
    void emitsWholeSentencesAsTheyArrive() {
        List<String> out = new ArrayList<>();
        SentenceSplitter s = new SentenceSplitter(out::add);
        s.accept("Okej, vi kör på matten");
        assertThat(out).isEmpty();
        s.accept(". Vad är första ");
        assertThat(out).containsExactly("Okej, vi kör på matten.");
        s.accept("uppgiften?");
        s.flush();
        assertThat(out).containsExactly("Okej, vi kör på matten.", "Vad är första uppgiften?");
    }

    @Test
    void doesNotSplitOnPageAbbreviationsOrDecimals() {
        assertThat(split("Läs s. 40–48 i kap. 4 och skriv t.ex. tre nyckelord. Sen tar vi 3.5 minuters paus."))
                .containsExactly("Läs s. 40–48 i kap. 4 och skriv t.ex. tre nyckelord.", "Sen tar vi 3.5 minuters paus.");
    }

    @Test
    void shortExclamationIsJoinedWithNextSentence() {
        assertThat(split("Bra! ", "Nu kör vi nästa kort direkt. ")).containsExactly("Bra! Nu kör vi nästa kort direkt.");
    }
}
