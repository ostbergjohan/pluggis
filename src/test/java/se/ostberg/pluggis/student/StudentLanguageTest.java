package se.ostberg.pluggis.student;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StudentLanguageTest {

    @Test
    void svenskaÄrStandard() {
        assertThat(StudentLanguage.normalize(null)).isEqualTo("sv");
        assertThat(StudentLanguage.normalize("sv")).isEqualTo("sv");
        assertThat(StudentLanguage.normalize("EN-gb")).isEqualTo("en");
        assertThat(new Student("Fred", "fred", "ABCDE").getLanguage()).isEqualTo("sv");
    }

    @Test
    void följerAktuellElev() {
        Student s = new Student("Sam", "sam", "QWERT");
        s.setLanguage("en");
        org.springframework.test.util.ReflectionTestUtils.setField(s, "id", 4242L);
        StudentLanguage.remember(s);
        StudentContext.run(4242L, () -> assertThat(StudentLanguage.english()).isTrue());
        StudentContext.run(4243L, () -> assertThat(StudentLanguage.current()).isEqualTo("sv"));
        StudentLanguage.forget(4242L);
    }
}
