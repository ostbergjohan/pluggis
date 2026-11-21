package se.ostberg.pluggis.flashcards;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeckRepository extends JpaRepository<Deck, Long> {

    List<Deck> findAllByOrderByCreatedAtDesc();
}
