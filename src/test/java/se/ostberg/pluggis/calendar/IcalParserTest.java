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

        assertThat(IcalParser.looksLikeSchoolwork("Studiedag", null)).isFalse();
        assertThat(IcalParser.looksLikeSchoolwork("Höstlov", "Skolan stängd")).isFalse();
        assertThat(IcalParser.looksLikeSchoolwork("Läxa: SO kap 2", null)).isTrue();
        assertThat(IcalParser.looksLikeSchoolwork("Inlämning NO-rapport", null)).isTrue();
    }

    @Test
    void calendarIsDecodedAsUtf8WhenServerSendsNoCharset() {
        byte[] body = "\uFEFFBEGIN:VCALENDAR\r\nSUMMARY:Aktivitetsdag förmiddag i Västertorpshallen!\r\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String noCharset = CalendarSyncService.decode(body, org.springframework.http.MediaType.parseMediaType("text/calendar"));
        String missingHeader = CalendarSyncService.decode(body, null);
        String latin1 = CalendarSyncService.decode("Väster".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                org.springframework.http.MediaType.parseMediaType("text/calendar; charset=ISO-8859-1"));

        assertThat(noCharset).startsWith("BEGIN:VCALENDAR").contains("förmiddag i Västertorpshallen");
        assertThat(missingHeader).contains("förmiddag");
        assertThat(latin1).isEqualTo("Väster");
    }

    @Test
    void webcalLinksBecomeHttps() {
        assertThat(CalendarSyncService.normalizeUrl("webcals://im2-api.infomentor.se/V1/x"))
                .isEqualTo("https://im2-api.infomentor.se/V1/x");
        assertThat(CalendarSyncService.normalizeUrl(" webcal://example.com/a.ics ")).isEqualTo("https://example.com/a.ics");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CalendarSyncService.normalizeUrl("ftp://x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
