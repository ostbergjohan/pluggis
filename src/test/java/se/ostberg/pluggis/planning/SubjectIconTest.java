package se.ostberg.pluggis.planning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectIconTest {

    @Test
    void ämnenFårSymbol() {
        assertThat(SubjectIcon.of("NO", "Prov fotosyntes")).isEqualTo("🧪");
        assertThat(SubjectIcon.of(null, "Matteprov kap 3")).isEqualTo("➗");
        assertThat(SubjectIcon.of("Engelska", "Glosor")).isEqualTo("🇬🇧");
        assertThat(SubjectIcon.of("Science", "Photosynthesis test")).isEqualTo("🧪");
        assertThat(SubjectIcon.of("Spanish", "Vocabulary ch. 5")).isEqualTo("🗣️");
        assertThat(SubjectIcon.of("Maths", "Equations")).isEqualTo("➗");
        assertThat(SubjectIcon.of("Art", "Drawing")).isEqualTo("🎨");
        assertThat(SubjectIcon.of(null, "Read part 2")).isEqualTo("📘");   // "art" bara som eget ord
        assertThat(SubjectIcon.of(null, "Nobelpriset")).isEqualTo("📘"); // "no" bara som helt ord
    }
}
