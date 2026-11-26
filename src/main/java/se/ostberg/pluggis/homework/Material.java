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

    /** Vilken elev raden hör till – sätts och filtreras automatiskt av Hibernate. */
    @org.hibernate.annotations.TenantId
    @jakarta.persistence.Column(name = "student_key", length = 20)
    private String studentKey;

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

    /** Finns en sparad sidbild (i MaterialImage, så att listor inte hämtar bilderna)? */
    private Boolean hasImage;

    @com.fasterxml.jackson.annotation.JsonIgnore
    private String imageType;

    /** Ordning inom en uppladdning (sida 1, 2 …). */
    private Integer page;

    /** Sidnumret som står tryckt på sidan i boken (t.ex. 182), om Claude kunde läsa det. */
    private Integer bookPage;

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
    public boolean isHasImage() { return Boolean.TRUE.equals(hasImage); }
    public String imageType() { return imageType; }
    public Integer getPage() { return page; }

    public void markImage(String type) {
        this.hasImage = true;
        this.imageType = type;
    }

    public void setPage(Integer page) {
        this.page = page;
    }
    public Integer getBookPage() { return bookPage; }
    public void setBookPage(Integer bookPage) {
        this.bookPage = bookPage != null && bookPage > 0 && bookPage < 10000 ? bookPage : null;
    }
    public Long getHomeworkId() { return homeworkId; }
    public String getFilename() { return filename; }
    public String getKind() { return kind; }
    public String getSummary() { return summary; }
    public String getText() { return text; }
    public Instant getCreatedAt() { return createdAt; }
}
