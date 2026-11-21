package se.ostberg.laxcoach.homework;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HomeworkRepository extends JpaRepository<Homework, Long> {

    List<Homework> findByStatusOrderByDueDateAscCreatedAtAsc(Homework.Status status);
}
