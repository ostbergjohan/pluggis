package se.ostberg.laxcoach.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Plockar ut ett JSON-objekt ur modelltext (klarar ```json-staket och text runt omkring). */
public final class JsonExtract {

    private JsonExtract() {
    }

    public static <T> T parse(String modelText, ObjectMapper mapper, Class<T> type) {
        if (modelText == null) {
            throw new IllegalArgumentException("Tomt svar från modellen");
        }
        int start = modelText.indexOf('{');
        int end = modelText.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Hittade inget JSON-objekt i modellens svar");
        }
        try {
            return mapper.readValue(modelText.substring(start, end + 1), type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Kunde inte tolka modellens JSON: " + e.getOriginalMessage(), e);
        }
    }
}
