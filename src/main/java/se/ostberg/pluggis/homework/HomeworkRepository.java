package se.ostberg.pluggis.homework;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HomeworkRepository extends JpaRepository<Homework, Long> {

    List<Homework> findByStatusOrderByDueDateAscCreatedAtAsc(Homework.Status status);

    Optional<Homework> findByExternalId(String externalId);

    List<Homework> findByExternalIdIsNotNullAndDueDate(java.time.LocalDate dueDate);

    List<Homework> findByExternalIdIsNotNull();
}
