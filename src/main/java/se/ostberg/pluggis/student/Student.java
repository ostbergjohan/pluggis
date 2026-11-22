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

    /** Pluggtid ("då"): t.ex. "16:15". Påminnelsen kommer då, och kalenderpasset läggs då. */
    @Column(length = 5)
    private String studyTime;

    /** Om-så-plan ("när"): t.ex. "efter mellanmålet". */
    @Column(length = 80)
    private String studyCue;
    /** Poäng påslaget för eleven (null = ja). Av: poängen räknas fortfarande men visas inte och nämns inte. */
    private Boolean pointsEnabled;

    /** Google Kalender att lägga planen i (kalender-id), eller null. */
    @Column(length = 200)
    private String googleCalendarId;

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
    public java.time.LocalTime studyTimeOrDefault() {
        try {
            return studyTime == null || studyTime.isBlank() ? java.time.LocalTime.of(16, 30) : java.time.LocalTime.parse(studyTime);
        } catch (java.time.format.DateTimeParseException e) {
            return java.time.LocalTime.of(16, 30);
        }
    }
    public String getStudyTime() { return studyTime; }
    public void setStudyTime(String t) { this.studyTime = t; }
    public String getStudyCue() { return studyCue == null ? "" : studyCue; }
    public void setStudyCue(String c) { this.studyCue = c; }
    public boolean isPointsEnabled() { return !Boolean.FALSE.equals(pointsEnabled); }
    public void setPointsEnabled(boolean on) { this.pointsEnabled = on; }
    public String getGoogleCalendarId() { return googleCalendarId; }
    public void setGoogleCalendarId(String id) { this.googleCalendarId = id; }
    public Integer getMaxMinutesPerDay() { return maxMinutesPerDay; }
    public void setMaxMinutesPerDay(Integer m) { this.maxMinutesPerDay = m; }
    public String getStudyDays() { return studyDays; }
    public void setStudyDays(String days) { this.studyDays = days; }
}
