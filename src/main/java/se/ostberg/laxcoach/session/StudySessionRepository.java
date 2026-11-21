package se.ostberg.laxcoach.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

    Optional<StudySession> findFirstByDeviceIdAndStatusOrderByStartedAtDesc(String deviceId, StudySession.Status status);

    List<StudySession> findByStatus(StudySession.Status status);

    List<StudySession> findTop20ByDeviceIdOrderByStartedAtDesc(String deviceId);
}
