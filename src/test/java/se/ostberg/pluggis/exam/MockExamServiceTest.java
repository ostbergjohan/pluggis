package se.ostberg.pluggis.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import se.ostberg.pluggis.claude.ClaudeClient;
import se.ostberg.pluggis.claude.ClaudeResponse;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.flashcards.Deck;
import se.ostberg.pluggis.flashcards.FlashcardService;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.homework.MaterialService;
import se.ostberg.pluggis.rewards.RewardsService;
import se.ostberg.pluggis.support.Prompts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MockExamServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final FlashcardService flashcards = mock(FlashcardService.class);
    private final RewardsService rewards = mock(RewardsService.class);
    private final MockExamService service = new MockExamService(claude, new Prompts(), mock(PluggisProperties.class, RETURNS_DEEP_STUBS),
            mock(HomeworkService.class), mock(MaterialService.class), flashcards, rewards, mapper);

    private static ClaudeResponse says(String json) {
        return new ClaudeResponse("x", List.of(ClaudeResponse.ContentBlock.text(json)), "end_turn", null);
    }

    @Test
    void görRättarOchSpararMissadeFrågor() {
        when(claude.newRequest(any(), anyString())).thenAnswer(i -> mapper.createObjectNode());
        when(claude.create(any(), eq("exam"))).thenReturn(says("""
                {"title":"Testprov: Fotosyntes","questions":[
                 {"type":"choice","question":"Vad behöver växter för att göra socker?","answer":"Ljus, vatten och koldioxid","options":["Jord och salt","Mörker","Kväve"],"topic":"Fotosyntesen"},
                 {"type":"cloze","question":"Det gröna färgämnet ___ fångar ljus.","answer":"klorofyll","options":["glukos","stärkelse","cellulosa"],"topic":"Klorofyll"},
                 {"type":"choice","question":"Vad kallas fotosyntesen?","answer":"Fotosyntes","options":["a","b","c"]},
                 {"type":"open","question":"Varför behöver vi växter för att kunna andas?","answer":"De gör syre vid fotosyntesen","key_points":["syre"],"topic":"Syre"}
                ]}"""));
        when(claude.create(any(), eq("exam-grade"))).thenReturn(says("{\"grades\":[{\"n\":3,\"points\":1,\"feedback\":\"Nästan!\"}]}"));
        Deck deck = mock(Deck.class);
        when(deck.getId()).thenReturn(77L);
        when(flashcards.createDeck(anyString(), any(), any(), any(), anyList())).thenReturn(deck);

        var started = service.start(null, 10);
        assertThat(started.questions()).hasSize(3); // frågan som avslöjar svaret är bortsorterad
        assertThat(started.maxPoints()).isEqualTo(4);
        assertThat(started.questions().get(0).choices()).hasSize(4).contains("Ljus, vatten och koldioxid");

        Map<Integer, String> answers = new HashMap<>();
        answers.put(1, "ljus, vatten och koldioxid"); // rätt, annan versal
        answers.put(2, "glukos");                     // fel
        answers.put(3, "Växterna gör luft");           // 1 av 2 enligt Claude
        var r = service.submit(started.id(), answers, "dev").orElseThrow();
        assertThat(r.points()).isEqualTo(2);
        assertThat(r.maxPoints()).isEqualTo(4);
        assertThat(r.review()).containsExactly("Klorofyll", "Syre");
        assertThat(r.reviewDeckId()).isEqualTo(77L);
        verify(rewards).onMockExam(eq("dev"), isNull(), eq("Testprov: Fotosyntes"));
        // Inlämnat två gånger → samma resultat, inga nya poäng
        assertThat(service.submit(started.id(), Map.of(), "dev").orElseThrow().points()).isEqualTo(2);
        verify(rewards, times(1)).onMockExam(any(), any(), any());
    }
}
