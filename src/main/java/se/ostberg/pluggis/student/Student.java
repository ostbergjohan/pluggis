package se.ostberg.pluggis.student;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * En elev. Läxor, plan, material och flashkort hör till en elev (se {@link StudentContext}).
 * Pass, poäng och samtal hör till elevens enhets-id ({@link #getDeviceId()}), så Tab5 och mobilchatten
 * för samma elev delar dem.
 */
@Entity
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** Fast kod (5 tecken) som eleven loggar in i mobilchatten med. */
    @Column(unique = true, length = 10)
    private String code;

    /** Enhets-id, t.ex. Tab5:ans "tab5-1". */
    @Column(unique = true, length = 60)
    private String deviceId;

    private String tone;

    @Column(length = 700)
    private String extra;

    @Column(length = 1000)
    private String calendarUrl;

    private Boolean calendarOnlySchoolwork;

    /** Planering: högst så många minuter plugg per dag (null = standard från konfigurationen). */
    private Integer maxMinutesPerDay;

    /** Planering: pluggdagar, t.ex. "MONDAY,TUESDAY,SUNDAY" (null = standard). */
    @Column(length = 80)
    private String studyDays;

    private Instant createdAt = Instant.now();

    protected Student() {
    }

    public Student(String name, String deviceId, String code) {
        this.name = name;
        this.deviceId = deviceId;
        this.code = code;
    }

    public Long getId() { return id; }
    public String getName() { return name == null ? "" : name; }
    public String getCode() { return code; }
    public String getDeviceId() { return deviceId; }
    public String getTone() { return tone; }
    public String getExtra() { return extra == null ? "" : extra; }
    public String getCalendarUrl() { return calendarUrl; }
    public boolean isCalendarOnlySchoolwork() { return calendarOnlySchoolwork == null || calendarOnlySchoolwork; }
    public Instant getCreatedAt() { return createdAt; }

    /** Nyckeln i databasens student_key-kolumner. */
    public String key() {
        return String.valueOf(id);
    }

    public void setName(String name) { this.name = name; }
    public void setCode(String code) { this.code = code; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public void setTone(String tone) { this.tone = tone; }
    public void setExtra(String extra) { this.extra = extra; }
    public void setCalendarUrl(String calendarUrl) { this.calendarUrl = calendarUrl; }
    public void setCalendarOnlySchoolwork(boolean only) { this.calendarOnlySchoolwork = only; }
    public Integer getMaxMinutesPerDay() { return maxMinutesPerDay; }
    public void setMaxMinutesPerDay(Integer m) { this.maxMinutesPerDay = m; }
    public String getStudyDays() { return studyDays; }
    public void setStudyDays(String days) { this.studyDays = days; }
}
