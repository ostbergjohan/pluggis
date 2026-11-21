package se.ostberg.laxcoach.flashcards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** JSON-formatet vi ber Claude om när kort genereras (se prompts/flashcards.md). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeneratedDeck(
        @JsonProperty("deck_name") String deckName,
        String subject,
        List<GeneratedCard> cards,
        String note) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeneratedCard(String front, String back, String hint) {
    }

    public List<FlashcardService.NewCard> toNewCards() {
        return cards == null ? List.of() : cards.stream()
                .map(c -> new FlashcardService.NewCard(c.front(), c.back(), c.hint()))
                .toList();
    }
}
