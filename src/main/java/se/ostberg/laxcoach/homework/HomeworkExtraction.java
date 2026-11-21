package se.ostberg.laxcoach.homework;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import se.ostberg.laxcoach.support.JsonExtract;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Det JSON-format vi ber Claude om när en bild på en uppgift skickas in. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HomeworkExtraction(List<Item> assignments, String note) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String subject,
            String title,
            String description,
            @JsonProperty("due_date") String dueDate,
            List<String> steps,
            String kind) {

        public Homework.Kind kindOrDefault() {
            return kind != null && kind.trim().equalsIgnoreCase("test") ? Homework.Kind.TEST : Homework.Kind.HOMEWORK;
        }

        public LocalDate dueDateOrNull() {
            if (dueDate == null || dueDate.isBlank()) {
                return null;
            }
            try {
                return LocalDate.parse(dueDate.trim());
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }

    public List<Item> assignmentsOrEmpty() {
        return assignments == null ? List.of() : assignments;
    }

    /**
     * Tolerant parser: klarar ```json-staket och text före/efter JSON-objektet.
     */
    public static HomeworkExtraction parse(String modelText, ObjectMapper mapper) {
        return JsonExtract.parse(modelText, mapper, HomeworkExtraction.class);
    }
}
