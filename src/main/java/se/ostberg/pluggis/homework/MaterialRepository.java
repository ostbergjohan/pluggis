package se.ostberg.pluggis.homework;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MaterialRepository extends JpaRepository<Material, Long> {

    List<Material> findByHomeworkIdOrderByCreatedAtAsc(Long homeworkId);

    void deleteByHomeworkId(Long homeworkId);
}
