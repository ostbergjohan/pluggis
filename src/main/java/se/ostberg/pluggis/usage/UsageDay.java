package se.ostberg.pluggis.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * Förbrukning per dag, elev, jobb (coach, planering …), modell och väg (api, abonnemang, azure).
 * Kostnaden räknas ut när anropet görs, så gamla dagar behåller det pris som gällde då.
 */
@Entity
@Table(name = "usage_day", indexes = @Index(name = "ix_usage_day", columnList = "usage_date"))
public class UsageDay {

    @Id
    @GeneratedValue
    private Long id;

    @Column(name = "usage_date", nullable = false)
    private LocalDate day;
    private Long studentId;
    @Column(nullable = false, length = 40)
    private String purpose;
    @Column(nullable = false, length = 80)
    private String model;
    /** "api" (betalas per token), "subscription" (Claude Code på abonnemanget) eller "azure". */
    @Column(nullable = false, length = 20)
    private String backend;

    private long calls;
    private long inputTokens;
    private long outputTokens;
    private long cacheReadTokens;
    private long cacheWriteTokens;
    private long characters;
    /** Vad det kostade, eller för abonnemanget: vad det hade kostat med API-nyckel. */
    private double costUsd;

    protected UsageDay() {
    }

    UsageDay(LocalDate day, Long studentId, String purpose, String model, String backend) {
        this.day = day;
        this.studentId = studentId;
        this.purpose = purpose;
        this.model = model;
        this.backend = backend;
    }

    void add(long input, long output, long cacheRead, long cacheWrite, long chars, double usd) {
        calls++;
        inputTokens += input;
        outputTokens += output;
        cacheReadTokens += cacheRead;
        cacheWriteTokens += cacheWrite;
        characters += chars;
        costUsd += usd;
    }

    public Long getId() { return id; }
    public LocalDate getDay() { return day; }
    public Long getStudentId() { return studentId; }
    public String getPurpose() { return purpose; }
    public String getModel() { return model; }
    public String getBackend() { return backend; }
    public long getCalls() { return calls; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getCacheReadTokens() { return cacheReadTokens; }
    public long getCacheWriteTokens() { return cacheWriteTokens; }
    public long getCharacters() { return characters; }
    public double getCostUsd() { return costUsd; }
}
