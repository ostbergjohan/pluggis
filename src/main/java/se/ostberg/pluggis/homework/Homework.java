package se.ostberg.pluggis.homework;

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
    /** OTHER = annat i kalendern (t.ex. aktivitetsdag) – räknas inte som läxa eller prov. */
    public enum Kind { HOMEWORK, TEST, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Vilken elev raden hör till – sätts och filtreras automatiskt av Hibernate. */
    @org.hibernate.annotations.TenantId
    @jakarta.persistence.Column(name = "student_key", length = 20)
    private String studentKey;

    private String subject;
    /** Hur viktigt (10–100 %, steg om 10). null = normalt (50 %). Styr hur mycket tid planen lägger. */
    private Integer weight;
    /** Föräldern har flyttat moment för hand – nattlig/automatisk omplanering låter planen vara. */
    private Boolean manualPlan;
    /** Var eleven var i uppläsningen av materialet: materialets id och meningens nummer. */
    /** Delområden att öva på var för sig (t.ex. "Siffror 1–100"), en per rad. Tas fram ur materialet. */
    @jakarta.persistence.Column(length = 2000)
    private String topics;

    private Long listenMaterialId;
    private Integer listenSentence;
    /** Material som lyssnats klart, kommaseparerade id:n. */
    @jakarta.persistence.Column(length = 1000)
    private String listenedIds;

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
    @Column(length = 20, columnDefinition = "varchar(20)")
    private Kind kind = Kind.HOMEWORK;

    /** Typ eller datum ändrat för hand – kalendersynken skriver då inte över dem. */
    private Boolean kindLocked;
    private Boolean dateLocked;

    /** Datumet i skolans kalender, även om datumet här ändrats eller tagits bort (för att känna igen händelsen). */
    private LocalDate calendarDate;

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
        this.calendarDate = dueDate;
        // Skriv inte över det ni själva fyllt i med kalenderns (ofta kortare) text.
        if (this.description == null || this.description.isBlank() || this.source == Source.CALENDAR) {
            this.description = description;
        }
        if (!Boolean.TRUE.equals(dateLocked)) {
            this.dueDate = dueDate;
        }
        if (!Boolean.TRUE.equals(kindLocked)) {
            this.kind = kind;
        }
    }

    public Integer getWeight() { return weight; }

    public boolean isManualPlan() { return Boolean.TRUE.equals(manualPlan); }

    public Long getListenMaterialId() { return listenMaterialId; }

    public Integer getListenSentence() { return listenSentence; }

    public java.util.List<Long> getListenedIds() {
        if (listenedIds == null || listenedIds.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(listenedIds.split(",")).map(String::trim).filter(x -> x.matches("\\d+")).map(Long::valueOf).toList();
    }

    /** Sparar var eleven är i uppläsningen; {@code listened} = material som lyssnats klart (null = oförändrat). */
    public void setListening(Long materialId, Integer sentence, java.util.Collection<Long> listened) {
        this.listenMaterialId = materialId;
        this.listenSentence = sentence == null ? null : Math.max(0, sentence);
        if (listened != null) {
            String joined = listened.stream().filter(java.util.Objects::nonNull).distinct().map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(","));
            this.listenedIds = joined.length() > 1000 ? joined.substring(0, 1000) : joined;
        }
    }

    public void setManualPlan(boolean manual) { this.manualPlan = manual; }

    /** Vikten som används i planeringen: 50 % om inget är valt. */
    public int weightOrDefault() { return weight == null ? 50 : weight; }

    public void setWeight(Integer weight) {
        if (weight == null) {
            return;
        }
        int w = Math.max(10, Math.min(100, Math.round(weight / 10f) * 10));
        this.weight = w;
    }

    /** Ändring för hand i webben: typ och/eller datum (null = ta bort datumet om clearDate). */
    public void editKindAndDate(Kind kind, LocalDate dueDate, boolean clearDate) {
        if (kind != null && kind != getKind()) {
            this.kind = kind;
            this.kindLocked = true;
        }
        if (clearDate && this.dueDate != null) {
            this.dueDate = null;
            this.dateLocked = true;
        } else if (dueDate != null && !dueDate.equals(this.dueDate)) {
            this.dueDate = dueDate;
            this.dateLocked = true;
        }
    }

    public boolean isOther() {
        return getKind() == Kind.OTHER;
    }

    /** Datum att känna igen kalenderhändelsen på. */
    public LocalDate getCalendarDate() {
        return calendarDate != null ? calendarDate : dueDate;
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

    public void edit(String subject, String title) {
        if (subject != null) {
            this.subject = subject.isBlank() ? null : subject;
        }
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
    }

    public void reopen() {
        this.status = Status.OPEN;
        this.completedAt = null;
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

    public List<String> getTopics() {
        return topics == null ? List.of() : Arrays.stream(topics.split("\n")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    public void setTopics(java.util.Collection<String> list) {
        this.topics = list == null || list.isEmpty() ? null : String.join("\n", list);
    }

    public List<String> getSteps() {
        return steps == null ? List.of() : Arrays.stream(steps.split("\n")).filter(s -> !s.isBlank()).toList();
    }
}
