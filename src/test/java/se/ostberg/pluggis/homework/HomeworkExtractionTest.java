package se.ostberg.pluggis.homework;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeworkExtractionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesJsonInsideCodeFence() {
        String text = """
                Här är resultatet:
                ```json
                {"assignments":[{"subject":"Matematik","title":"Uppgift 4.12–4.18",
                  "description":"Räkna med bråk","due_date":"2026-11-27",
                  "steps":["Läs exemplet","Gör 4.12"]}],"note":null}
                ```
                """;

        HomeworkExtraction result = HomeworkExtraction.parse(text, mapper);

        assertThat(result.assignmentsOrEmpty()).hasSize(1);
        HomeworkExtraction.Item item = result.assignmentsOrEmpty().get(0);
        assertThat(item.subject()).isEqualTo("Matematik");
        assertThat(item.dueDateOrNull()).isEqualTo(LocalDate.of(2026, 11, 27));
        assertThat(item.steps()).containsExactly("Läs exemplet", "Gör 4.12");
    }

    @Test
    void toleratesMissingOrBadDate() {
        String text = "{\"assignments\":[{\"title\":\"Glosor\",\"due_date\":\"på fredag\"}]}";

        HomeworkExtraction result = HomeworkExtraction.parse(text, mapper);

        assertThat(result.assignmentsOrEmpty().get(0).dueDateOrNull()).isNull();
    }

    @Test
    void emptyAssignmentsWithNote() {
        HomeworkExtraction result = HomeworkExtraction.parse(
                "{\"assignments\":[],\"note\":\"Bilden är suddig\"}", mapper);

        assertThat(result.assignmentsOrEmpty()).isEmpty();
        assertThat(result.note()).isEqualTo("Bilden är suddig");
    }

    @Test
    void rejectsTextWithoutJson() {
        assertThatThrownBy(() -> HomeworkExtraction.parse("Jag kan inte läsa bilden.", mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
