package se.ostberg.pluggis.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextFixTest {

    @Test
    void repairsUtf8ReadAsLatin1AndLeavesGoodTextAlone() {
        assertThat(TextFix.fixMojibake("Aktivitetsdag fÃ¶rmiddag i VÃ¤stertorpshallen!"))
                .isEqualTo("Aktivitetsdag förmiddag i Västertorpshallen!");
        assertThat(TextFix.fixMojibake("Förmiddag i Västertorp")).isEqualTo("Förmiddag i Västertorp");
        assertThat(TextFix.fixMojibake(null)).isNull();
    }

    @Test
    void keyIgnoresCaseSpacingAndBrokenEncoding() {
        assertThat(TextFix.key("  Engelska:  GlosfÃ¶rhÃ¶r ")).isEqualTo(TextFix.key("engelska: glosförhör"));
    }
}
