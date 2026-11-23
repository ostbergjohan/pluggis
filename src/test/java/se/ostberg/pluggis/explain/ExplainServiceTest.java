package se.ostberg.pluggis.explain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import se.ostberg.pluggis.claude.ClaudeClient;
import se.ostberg.pluggis.claude.ClaudeResponse;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.homework.HomeworkService;
import se.ostberg.pluggis.homework.MaterialService;
import se.ostberg.pluggis.rewards.RewardsService;
import se.ostberg.pluggis.support.Prompts;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ExplainServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final RewardsService rewards = mock(RewardsService.class);
    private final HomeworkService homework = mock(HomeworkService.class);
    private final ExplainService explain = new ExplainService(claude, new Prompts(),
            mock(PluggisProperties.class, RETURNS_DEEP_STUBS), homework, mock(MaterialService.class), rewards, mapper);

    private static ClaudeResponse says(String json) {
        return new ClaudeResponse("x", List.of(ClaudeResponse.ContentBlock.text(json)), "end_turn", null);
    }

    @Test
    void frågarFöljerUppOchSammanfattar() {
        when(claude.newRequest(any(), anyString())).thenAnswer(i -> mapper.createObjectNode());
        when(homework.get(anyLong())).thenReturn(Optional.empty());
        when(claude.create(any(), eq("explain-start"))).thenReturn(says("""
                {"concept":"Fotosyntesen","question":"Hur kan växter äta ljus?","key_points":["Ljus","Vatten och koldioxid","Socker och syre"]}"""));
        when(claude.create(any(), eq("explain-answer"))).thenReturn(
                says("{\"covered\":[0],\"reply\":\"Men vad behöver de mer?\",\"done\":false}"),
                says("{\"covered\":[1],\"reply\":\"Och vad blir det av det?\",\"done\":false}"),
                says("{\"covered\":[],\"reply\":\"Tack!\",\"done\":false}"));

        var t = explain.start(null);
        assertThat(t.reply()).contains("ljus");
        var a1 = explain.answer(t.id(), "De tar ljus från solen", "dev").orElseThrow();
        assertThat(a1.done()).isFalse();
        var a2 = explain.answer(t.id(), "Och vatten och koldioxid", "dev").orElseThrow();
        assertThat(a2.done()).isFalse();
        // Två följdfrågor är max – nästa svar avslutar även om Claude vill fortsätta
        var a3 = explain.answer(t.id(), "Vet inte", "dev").orElseThrow();
        assertThat(a3.done()).isTrue();
        assertThat(a3.covered()).containsExactly("Ljus", "Vatten och koldioxid");
        assertThat(a3.missing()).containsExactly("Socker och syre");
        verify(rewards, times(1)).onExplained("dev", "Fotosyntesen");
        // Okänd runda
        assertThat(explain.answer("finns-inte", "hej", "dev")).isEmpty();
    }
}
