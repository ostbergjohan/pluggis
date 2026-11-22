package se.ostberg.pluggis.planning;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;
import java.time.LocalDate;

/** Ett litet, konkret pluggmoment en viss dag, t.ex. "Läs s. 40–48 och skriv 3 nyckelord". */
@Entity
public class PlanItem {

    public enum Type { READ, PRACTICE, WRITE, FLASHCARDS, REVIEW, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Vilken elev raden hör till – sätts och filtreras automatiskt av Hibernate. */
    @org.hibernate.annotations.TenantId
    @jakarta.persistence.Column(name = "student_key", length = 20)
    private String studentKey;

    private Long homeworkId;

    private LocalDate planDate;

    @Column(length = 500)
    private String task;

    private int minutes;

    @Enumerated(EnumType.STRING)
    private Type type;

    private boolean done;

    private Instant doneAt;

    /** Ordning inom dagen när föräldern flyttat för hand; null = skapandeordning. */
    private Integer sortOrder;

    /** Sortering: dag, handordning (osorterade sist), sedan när momentet skapades. */
    public static final java.util.Comparator<PlanItem> ORDER = java.util.Comparator.comparing(PlanItem::getPlanDate)
            .thenComparing(PlanItem::getSortOrder, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
            .thenComparing(PlanItem::getId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));

    protected PlanItem() {
    }

    public PlanItem(Long homeworkId, LocalDate planDate, String task, int minutes, Type type) {
        this.homeworkId = homeworkId;
        this.planDate = planDate;
        this.task = task;
        this.minutes = minutes;
        this.type = type == null ? Type.OTHER : type;
    }

    public void markDone(Instant now) {
        this.done = true;
        this.doneAt = now;
    }

    public void markUndone() {
        this.done = false;
        this.doneAt = null;
    }

    /** Flyttad för hand (dra och släpp i föräldravyn). */
    public void moveTo(LocalDate date, int order) {
        this.planDate = date;
        this.sortOrder = order;
    }

    public Integer getSortOrder() { return sortOrder; }

    public Long getId() { return id; }
    public Long getHomeworkId() { return homeworkId; }
    public LocalDate getPlanDate() { return planDate; }
    public String getTask() { return task; }
    public int getMinutes() { return minutes; }
    public Type getType() { return type; }
    public boolean isDone() { return done; }
    public Instant getDoneAt() { return doneAt; }
}
