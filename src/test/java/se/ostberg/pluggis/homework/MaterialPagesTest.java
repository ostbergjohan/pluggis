package se.ostberg.pluggis.homework;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaterialPagesTest {

    @Test
    void enSidaEllerEttUppslag() {
        Material m = new Material(1L, "sida.jpg", "IMAGE", null, "text");
        m.setBookPages(182, null);
        assertThat(m.getBookPage()).isEqualTo(182);
        assertThat(m.getBookPageTo()).isNull();

        m.setBookPages(182, 183);
        assertThat(m.getBookPageTo()).isEqualTo(183);
        assertThat(MaterialService.pagesLabel(m)).isEqualTo("182–183");
    }

    @Test
    void ogiltigaSidorRensas() {
        Material m = new Material(1L, "sida.jpg", "IMAGE", null, "text");
        m.setBookPages(182, 180);            // sista före första
        assertThat(m.getBookPageTo()).isNull();
        m.setBookPages(0, null);             // ingen sida
        assertThat(m.getBookPage()).isNull();
        m.setBookPages(null, 183);           // "till" utan "från"
        assertThat(m.getBookPage()).isNull();
        assertThat(m.getBookPageTo()).isNull();
    }
}
