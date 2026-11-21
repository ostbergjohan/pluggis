package se.ostberg.pluggis.stats;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ReviewLogRepository extends JpaRepository<ReviewLog, Long> {

    List<ReviewLog> findByDeviceIdAndReviewedOnGreaterThanEqual(String deviceId, LocalDate from);
}
