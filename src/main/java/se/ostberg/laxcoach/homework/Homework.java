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

    public enum Source { MANUAL, PHOTO, COACH }

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
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }

    public List<String> getSteps() {
        return steps == null ? List.of() : Arrays.stream(steps.split("\n")).filter(s -> !s.isBlank()).toList();
    }
}
