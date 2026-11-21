package se.ostberg.laxcoach.planning;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface PlanItemRepository extends JpaRepository<PlanItem, Long> {

    List<PlanItem> findByHomeworkIdOrderByPlanDateAscIdAsc(Long homeworkId);

    List<PlanItem> findByDoneFalseAndPlanDateLessThanEqualOrderByPlanDateAscIdAsc(LocalDate day);

    List<PlanItem> findByPlanDateBetweenOrderByPlanDateAscIdAsc(LocalDate from, LocalDate to);

    @Transactional
    @Modifying
    @Query("delete from PlanItem p where p.homeworkId = ?1 and p.done = false")
    int deleteOpenForHomework(Long homeworkId);
}
