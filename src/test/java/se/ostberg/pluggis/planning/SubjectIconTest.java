package se.ostberg.pluggis.planning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectIconTest {

    @Test
    void ämnenFårSymbol() {
        assertThat(SubjectIcon.of("NO", "Prov fotosyntes")).isEqualTo("🧪");
        assertThat(SubjectIcon.of(null, "Matteprov kap 3")).isEqualTo("➗");
        assertThat(SubjectIcon.of("Engelska", "Glosor")).isEqualTo("🇬🇧");
        assertThat(SubjectIcon.of(null, "Nobelpriset")).isEqualTo("📘"); // "no" bara som helt ord
    }
}
