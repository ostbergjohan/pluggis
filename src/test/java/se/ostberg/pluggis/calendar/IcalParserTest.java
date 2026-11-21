package se.ostberg.pluggis.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IcalParserTest {

    private static final ZoneId STHLM = ZoneId.of("Europe/Stockholm");

    @Test
    void readsEventsWithFoldedLinesAndEscapes() {
        String ics = String.join("\r\n",
                "BEGIN:VCALENDAR",
                "BEGIN:VEVENT",
                "UID:abc-1@skolan",
                "DTSTART;VALUE=DATE:20261127",
                "SUMMARY:Engelska: Glosförhör kap 3",
                "DESCRIPTION:Glosorna på s. 42\\, alla ord i rutan.\\nLär er stavningen",
                "  också.",
                "END:VEVENT",
                "BEGIN:VTODO",
                "UID:abc-2@skolan",
                "DUE:20261201T225959Z",
                "SUMMARY:MA - Inlämning problemlösning",
                "END:VTODO",
                "END:VCALENDAR");

        List<IcalParser.Entry> entries = IcalParser.parse(ics, STHLM);

        assertThat(entries).hasSize(2);
        IcalParser.Entry first = entries.get(0);
        assertThat(first.uid()).isEqualTo("abc-1@skolan");
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(first.description()).isEqualTo("Glosorna på s. 42, alla ord i rutan.\nLär er stavningen också.");
        // 22:59:59 UTC är 23:59:59 i Stockholm samma dag
        assertThat(entries.get(1).date()).isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    void detectsTestsAndSubjects() {
        assertThat(IcalParser.looksLikeTest("Engelska: Glosförhör kap 3", null)).isTrue();
        assertThat(IcalParser.looksLikeTest("NO-prov fotosyntes", null)).isTrue();
        assertThat(IcalParser.looksLikeTest("MA - Inlämning problemlösning", null)).isFalse();

        assertThat(IcalParser.subjectFrom("Engelska: Glosförhör kap 3")).isEqualTo("Engelska");
        assertThat(IcalParser.subjectFrom("MA - Inlämning")).isEqualTo("MA");
        assertThat(IcalParser.subjectFrom("Studiedag")).isNull();
    }
}
