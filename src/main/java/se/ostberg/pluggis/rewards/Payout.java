package se.ostberg.pluggis.rewards;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** En utbetalning som en förälder markerat (t.ex. Swish av veckopengen). */
@Entity
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviceId;

    private BigDecimal amountSek;

    /** Måndagen i veckan som utbetalningen avser. */
    private LocalDate weekStart;

    private String note;

    private Instant paidAt;

    protected Payout() {
    }

    public Payout(String deviceId, BigDecimal amountSek, LocalDate weekStart, String note, Instant paidAt) {
        this.deviceId = deviceId;
        this.amountSek = amountSek;
        this.weekStart = weekStart;
        this.note = note;
        this.paidAt = paidAt;
    }

    public Long getId() { return id; }
    public String getDeviceId() { return deviceId; }
    public BigDecimal getAmountSek() { return amountSek; }
    public LocalDate getWeekStart() { return weekStart; }
    public String getNote() { return note; }
    public Instant getPaidAt() { return paidAt; }
}
