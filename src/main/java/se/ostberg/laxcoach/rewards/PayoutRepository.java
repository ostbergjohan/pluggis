package se.ostberg.laxcoach.rewards;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    @Query("select coalesce(sum(p.amountSek), 0) from Payout p where p.deviceId = ?1 and p.weekStart = ?2")
    BigDecimal paidForWeek(String deviceId, LocalDate weekStart);

    List<Payout> findTop20ByDeviceIdOrderByPaidAtDesc(String deviceId);
}
