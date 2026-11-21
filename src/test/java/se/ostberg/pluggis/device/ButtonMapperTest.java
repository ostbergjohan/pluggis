package se.ostberg.pluggis.device;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ButtonMapperTest {

    @Test
    void redButtonTakesPhoto() {
        assertThat(ButtonMapper.map("photo", "click", false)).containsInstanceOf(ButtonMapper.TakePhoto.class);
    }

    @Test
    void longPressOnRedPhotographsForFlashcards() {
        var action = (ButtonMapper.TakePhoto) ButtonMapper.map("photo", "long", false).orElseThrow();
        assertThat(action.forFlashcards()).isTrue();
    }

    @Test
    void blueClickStartsOrAsksForHelpDependingOnSession() {
        var idle = ButtonMapper.map("main", "click", false).orElseThrow();
        var busy = ButtonMapper.map("main", "click", true).orElseThrow();

        assertThat(((ButtonMapper.SayToCoach) idle).text()).contains("börja");
        assertThat(((ButtonMapper.SayToCoach) busy).text()).contains("hjälp");
    }

    @Test
    void unknownButtonIsIgnored() {
        assertThat(ButtonMapper.map("green", "click", false)).isEmpty();
    }
}
