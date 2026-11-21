package se.ostberg.pluggis.coach;

import java.util.function.Consumer;

/**
 * Samlar strömmad text och lämnar ifrån sig hela meningar, så att uppläsningen kan börja
 * innan Claude skrivit klart. Delar på . ! ? och radbrytning följt av mellanslag/slut,
 * men inte på t.ex. "s. 40" eller "kap. 4" (förkortning + siffra) eller decimaltal.
 */
public class SentenceSplitter {

    static final int MIN_LENGTH = 12;

    private final Consumer<String> onSentence;
    private final StringBuilder buffer = new StringBuilder();

    public SentenceSplitter(Consumer<String> onSentence) {
        this.onSentence = onSentence;
    }

    public void accept(String chunk) {
        buffer.append(chunk);
        int cut;
        while ((cut = findBoundary()) > 0) {
            String sentence = buffer.substring(0, cut).trim();
            buffer.delete(0, cut);
            if (!sentence.isEmpty()) {
                onSentence.accept(sentence);
            }
        }
    }

    public void flush() {
        String rest = buffer.toString().trim();
        buffer.setLength(0);
        if (!rest.isEmpty()) {
            onSentence.accept(rest);
        }
    }

    /** Index efter slutet på första hela meningen, eller -1. */
    private int findBoundary() {
        for (int i = 0; i < buffer.length() - 1; i++) {
            char c = buffer.charAt(i);
            char next = buffer.charAt(i + 1);
            if (c == '\n') {
                if (i + 1 >= MIN_LENGTH) {
                    return i + 1;
                }
                continue;
            }
            if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(next)) {
                if (i + 1 < MIN_LENGTH) {
                    continue; // för kort – vänta in mer, t.ex. "Bra!" läses ihop med nästa mening
                }
                if (c == '.' && looksLikeAbbreviation(i)) {
                    continue;
                }
                return i + 1;
            }
        }
        return -1;
    }

    /** "s. 40", "kap. 4", "t.ex. " – ordet före punkten är kort och/eller nästa tecken är en siffra. */
    private boolean looksLikeAbbreviation(int dot) {
        int j = dot + 1;
        while (j < buffer.length() && buffer.charAt(j) == ' ') {
            j++;
        }
        if (j < buffer.length() && Character.isDigit(buffer.charAt(j))) {
            return true;
        }
        int start = dot - 1;
        while (start >= 0 && Character.isLetter(buffer.charAt(start))) {
            start--;
        }
        String word = buffer.substring(start + 1, dot).toLowerCase();
        return word.equals("t.ex") || word.equals("ex") || word.equals("s") || word.equals("kap")
                || word.equals("bl.a") || word.equals("ca") || word.equals("nr") || word.equals("st")
                || (start >= 0 && buffer.charAt(start) == '.');
    }
}
