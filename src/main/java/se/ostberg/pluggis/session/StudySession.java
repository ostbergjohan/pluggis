package se.ostberg.pluggis.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Duration;
import java.time.Instant;

@Entity
public class StudySession {

    public enum Status { ACTIVE, FINISHED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviceId;

    @Column(length = 1000)
    private String task;

    private int plannedMinutes;

    private Instant startedAt;

    private Instant endsAt;

    private Instant finishedAt;

    @Column(length = 2000)
    private String summary;

    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    /** Satt när enheten fått "tiden är ute", så att vi bara säger det en gång. */
    private boolean timeUpNotified;

    protected StudySession() {
    }

    public StudySession(String deviceId, String task, int plannedMinutes, Instant now) {
        this.deviceId = deviceId;
        this.task = task;
        this.plannedMinutes = plannedMinutes;
        this.startedAt = now;
        this.endsAt = now.plus(Duration.ofMinutes(plannedMinutes));
    }

    /** Ett avslutat "pass" av övningstid i Öva (startad och avslutad i efterhand). */
    public static StudySession practice(String deviceId, String task, Instant start, Instant end) {
        int minutes = (int) Math.max(1, Duration.between(start, end).toMinutes());
        StudySession s = new StudySession(deviceId, task, minutes, start);
        s.finish("Övning i appen", end);
        return s;
    }

    public void finish(String summary, Instant now) {
        this.status = Status.FINISHED;
        this.summary = summary;
        this.finishedAt = now;
    }

    public long remainingSeconds(Instant now) {
        return Math.max(0, Duration.between(now, endsAt).getSeconds());
    }

    public boolean isTimeUp(Instant now) {
        return !now.isBefore(endsAt);
    }

    public void markTimeUpNotified() {
        this.timeUpNotified = true;
    }

    public Long getId() { return id; }
    public String getDeviceId() { return deviceId; }
    public String getTask() { return task; }
    public int getPlannedMinutes() { return plannedMinutes; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndsAt() { return endsAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getSummary() { return summary; }
    public Status getStatus() { return status; }
    public boolean isTimeUpNotified() { return timeUpNotified; }
}
