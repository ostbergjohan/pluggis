package se.ostberg.pluggis.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Elevkoden räcker för Tab5: allt skärmen gör, men inget av förälderns. */
class ChatAccessAllowedTest {

    @Test
    void tab5MedElevkod() {
        for (String call : new String[]{
                "GET /api/chat/me", "GET /api/homework", "GET /api/homework/7/material", "PUT /api/homework/7/listen",
                "GET /api/homework/7/topics", "GET /api/plan/upcoming", "POST /api/plan/items/12/done",
                "POST /api/flashcards/quiz", "GET /api/flashcards/decks/3/next", "GET /api/flashcards/decks/3/cards",
                "POST /api/flashcards/cards/9/grade", "POST /api/games/order", "POST /api/games/done",
                "POST /api/explain/start", "POST /api/exam/start", "POST /api/practice/start",
                "POST /api/speech/preview", "POST /api/speech/transcribe"}) {
            String[] mp = call.split(" ", 2);
            assertThat(ChatAccess.allowed(mp[0], mp[1])).as(call).isTrue();
        }
    }

    @Test
    void inteForalderns() {
        assertThat(ChatAccess.allowed("DELETE", "/api/homework/7")).isFalse();
        assertThat(ChatAccess.allowed("POST", "/api/plan/replan-all")).isFalse();
        assertThat(ChatAccess.allowed("PUT", "/api/homework/material/5/pages")).isFalse();
        assertThat(ChatAccess.allowed("POST", "/api/homework/7/material/pages")).isFalse();
    }
}
