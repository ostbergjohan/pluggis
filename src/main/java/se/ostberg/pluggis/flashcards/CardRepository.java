package se.ostberg.pluggis.flashcards;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CardRepository extends JpaRepository<Card, Long> {

    List<Card> findByDeckIdOrderByIdAsc(Long deckId);

    Optional<Card> findFirstByDeckIdAndDueAtLessThanEqualOrderByBoxAscDueAtAsc(Long deckId, Instant now);

    Optional<Card> findFirstByDeckIdOrderByDueAtAsc(Long deckId);

    long countByDeckId(Long deckId);

    void deleteByDeckId(Long deckId);

    long countByDeckIdAndDueAtLessThanEqual(Long deckId, Instant now);

    long countByDeckIdAndBoxGreaterThanEqual(Long deckId, int box);
}
