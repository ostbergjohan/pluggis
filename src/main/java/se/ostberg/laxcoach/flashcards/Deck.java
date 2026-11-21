package se.ostberg.laxcoach.flashcards;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

@Entity
public class Deck {

    public enum Source { MANUAL, GENERATED, PHOTO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String subject;

    /** Koppling till en läxa/ett prov om leken skapades för det. */
    private Long homeworkId;

    @Enumerated(EnumType.STRING)
    private Source source;

    private Instant createdAt = Instant.now();

    protected Deck() {
    }

    public Deck(String name, String subject, Long homeworkId, Source source) {
        this.name = name;
        this.subject = subject;
        this.homeworkId = homeworkId;
        this.source = source;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSubject() { return subject; }
    public Long getHomeworkId() { return homeworkId; }
    public Source getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
}
