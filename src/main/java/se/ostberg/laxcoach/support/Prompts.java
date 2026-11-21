package se.ostberg.laxcoach.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Läser promptfilerna i src/main/resources/prompts en gång vid start. */
@Component
public class Prompts {

    private final String coach;
    private final String extraction;
    private final String flashcards;
    private final String studyPlan;

    public Prompts() {
        this.coach = load("prompts/coach-system.md");
        this.extraction = load("prompts/homework-extraction.md");
        this.flashcards = load("prompts/flashcards.md");
        this.studyPlan = load("prompts/study-plan.md");
    }

    public String studyPlan() {
        return studyPlan;
    }

    public String flashcards() {
        return flashcards;
    }

    public String coach() {
        return coach;
    }

    public String extraction() {
        return extraction;
    }

    private static String load(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Kunde inte läsa " + path, e);
        }
    }
}
