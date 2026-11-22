package se.ostberg.pluggis.rewards;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface PointEventRepository extends JpaRepository<PointEvent, Long> {

    long countByDeviceIdAndRefKeyAndAwardedOn(String deviceId, String refKey, LocalDate awardedOn);

    boolean existsByDeviceIdAndKindAndAwardedOn(String deviceId, PointEvent.Kind kind, LocalDate awardedOn);

    @Query("select coalesce(sum(p.points), 0) from PointEvent p where p.deviceId = ?1 and p.awardedOn between ?2 and ?3")
    long sumPoints(String deviceId, LocalDate from, LocalDate to);

    @Query("select coalesce(sum(p.points), 0) from PointEvent p where p.deviceId = ?1")
    long sumAllPoints(String deviceId);

    @Query("select distinct p.awardedOn from PointEvent p where p.deviceId = ?1 and p.kind = ?2 and p.awardedOn >= ?3 order by p.awardedOn desc")
    List<LocalDate> daysWithKind(String deviceId, PointEvent.Kind kind, LocalDate since);

    List<PointEvent> findTop50ByDeviceIdOrderByCreatedAtDesc(String deviceId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from PointEvent p where p.deviceId = ?1")
    int deleteByDeviceId(String deviceId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from PointEvent p where p.deviceId = ?1 and p.awardedOn between ?2 and ?3")
    int deleteByDeviceIdAndAwardedOnBetween(String deviceId, LocalDate from, LocalDate to);

    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from PointEvent p where p.deviceId = ?1 and p.refKey = ?2")
    int deleteByDeviceIdAndRefKey(String deviceId, String refKey);

    @org.springframework.data.jpa.repository.Modifying
    @Query("delete from PointEvent p where p.id = ?1 and p.deviceId = ?2")
    int deleteByIdAndDeviceId(Long id, String deviceId);

    List<PointEvent> findByDeviceIdAndAwardedOnGreaterThanEqual(String deviceId, LocalDate from);
}
