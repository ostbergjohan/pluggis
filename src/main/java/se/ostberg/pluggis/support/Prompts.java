package se.ostberg.pluggis.support;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Läser promptfilerna i src/main/resources/prompts en gång vid start. */
@Component
public class Prompts {

    private final String coach;
    private final String coachState;
    private final String extraction;
    private final String flashcards;
    private final String studyPlan;
    private final String material;
    private final String quiz;
    private final String explain;

    public Prompts() {
        this.coach = load("prompts/coach-system.md");
        this.coachState = load("prompts/coach-state.md");
        this.extraction = load("prompts/homework-extraction.md");
        this.flashcards = load("prompts/flashcards.md");
        this.studyPlan = load("prompts/study-plan.md");
        this.material = load("prompts/material.md");
        this.quiz = load("prompts/quiz.md");
        this.explain = load("prompts/explain.md");
    }

    public String explain() {
        return explain;
    }

    public String quiz() {
        return quiz;
    }

    public String material() {
        return material;
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

    /** Mall för läget just nu – fylls i varje anrop och ligger efter cache-markeringen. */
    public String coachState() {
        return coachState;
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
