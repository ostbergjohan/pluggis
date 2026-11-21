package se.ostberg.laxcoach.homework;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@Entity
public class Homework {

    public enum Status { OPEN, DONE }

    public enum Source { MANUAL, PHOTO, COACH, CALENDAR }

    /** Läxa (inlämning/uppgift) eller prov/förhör – styr hur planeringen görs. */
    public enum Kind { HOMEWORK, TEST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String subject;

    private String title;

    @Column(length = 4000)
    private String description;

    private LocalDate dueDate;

    /** Delsteg, ett per rad. */
    @Column(length = 4000)
    private String steps;

    @Enumerated(EnumType.STRING)
    private Status status = Status.OPEN;

    @Enumerated(EnumType.STRING)
    private Source source;

    @Enumerated(EnumType.STRING)
    private Kind kind = Kind.HOMEWORK;

    /** UID från skolans kalender, så att samma händelse uppdateras i stället för att dubbleras. */
    @Column(unique = true)
    private String externalId;

    /** Kortlek att repetera inför provet. */
    private Long deckId;

    private Instant createdAt = Instant.now();

    private Instant completedAt;

    protected Homework() {
    }

    public Homework(String subject, String title, String description, LocalDate dueDate, List<String> steps, Source source) {
        this.subject = subject;
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.steps = steps == null || steps.isEmpty() ? null : String.join("\n", steps);
        this.source = source;
    }

    public void updateFromCalendar(String subject, String title, String description, LocalDate dueDate, Kind kind) {
        this.subject = subject;
        this.title = title;
        // Skriv inte över det ni själva fyllt i med kalenderns (ofta kortare) text.
        if (this.description == null || this.description.isBlank() || this.source == Source.CALENDAR) {
            this.description = description;
        }
        this.dueDate = dueDate;
        this.kind = kind;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    /** Vad som ingår, t.ex. "kapitel 3–4 + glosor s. 42", och delstegen. */
    public void updateContent(String description, List<String> steps, Kind kind, LocalDate dueDate) {
        if (description != null) {
            this.description = description;
            if (this.source == Source.CALENDAR) {
                this.source = Source.MANUAL; // skyddar texten mot nästa kalenderimport
            }
        }
        if (steps != null) {
            this.steps = steps.isEmpty() ? null : String.join("\n", steps);
        }
        if (kind != null) {
            this.kind = kind;
        }
        if (dueDate != null) {
            this.dueDate = dueDate;
        }
    }

    public void linkDeck(Long deckId) {
        this.deckId = deckId;
    }

    public void markDone() {
        this.status = Status.DONE;
        this.completedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSubject() { return subject; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public LocalDate getDueDate() { return dueDate; }
    public Status getStatus() { return status; }
    public Source getSource() { return source; }
    public Kind getKind() { return kind == null ? Kind.HOMEWORK : kind; }
    public String getExternalId() { return externalId; }
    public Long getDeckId() { return deckId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    public List<String> getSteps() {
        return steps == null ? List.of() : Arrays.stream(steps.split("\n")).filter(s -> !s.isBlank()).toList();
    }
}
