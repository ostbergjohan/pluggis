package se.ostberg.pluggis.student;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByCodeIgnoreCase(String code);

    Optional<Student> findByDeviceId(String deviceId);

    List<Student> findAllByOrderByIdAsc();
}
