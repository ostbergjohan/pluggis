package se.ostberg.pluggis.usage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface UsageDayRepository extends JpaRepository<UsageDay, Long> {

    Optional<UsageDay> findFirstByDayAndStudentIdAndPurposeAndModelAndBackend(
            LocalDate day, Long studentId, String purpose, String model, String backend);

    List<UsageDay> findByDayGreaterThanEqualOrderByDayAsc(LocalDate from);
}
