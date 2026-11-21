package se.ostberg.pluggis.homework;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/** Studiematerial kopplat till en läxa eller ett prov (skärmdump, Word, PDF, text). */
@Entity
public class Material {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long homeworkId;

    private String filename;

    private String kind;

    /** Kort sammanfattning av vad materialet tar upp. */
    @Column(length = 2000)
    private String summary;

    /** Texten i materialet (läst eller avläst av Claude) – används för flashkort och coachen. */
    @Column(length = 60000)
    private String text;

    private Instant createdAt = Instant.now();

    protected Material() {
    }

    public Material(Long homeworkId, String filename, String kind, String summary, String text) {
        this.homeworkId = homeworkId;
        this.filename = filename;
        this.kind = kind;
        this.summary = summary;
        this.text = text;
    }

    public Long getId() { return id; }
    public Long getHomeworkId() { return homeworkId; }
    public String getFilename() { return filename; }
    public String getKind() { return kind; }
    public String getSummary() { return summary; }
    public String getText() { return text; }
    public Instant getCreatedAt() { return createdAt; }
}
