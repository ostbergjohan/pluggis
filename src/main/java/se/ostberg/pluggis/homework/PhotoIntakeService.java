package se.ostberg.pluggis.homework;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import se.ostberg.pluggis.claude.ClaudeClient;
import se.ostberg.pluggis.claude.ClaudeResponse;
import se.ostberg.pluggis.config.PluggisProperties;
import se.ostberg.pluggis.support.Prompts;
import se.ostberg.pluggis.support.UploadedDocument;

import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Tar emot en bild (från Tab5-kameran eller iOS-genvägen), låter Claude läsa ut
 * uppgifterna som JSON och sparar dem som läxor.
 */
@Service
public class PhotoIntakeService {

    /** Claude tar bilder upp till ca 5 MB. */
    static final long MAX_BYTES = 5L * 1024 * 1024;
    static final Set<String> MEDIA_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    public record IntakeResult(List<Homework> saved, String note) {
    }

    /** Publiceras så att enheten kan berätta vad som lades till. */
    public record HomeworkAddedEvent(String deviceId, List<Homework> saved) {
    }

    private final ClaudeClient claude;
    private final HomeworkService homework;
    private final ObjectMapper mapper;
    private final Prompts prompts;
    private final PluggisProperties props;
    private final ApplicationEventPublisher events;

    public PhotoIntakeService(ClaudeClient claude, HomeworkService homework, ObjectMapper mapper,
                              Prompts prompts, PluggisProperties props, ApplicationEventPublisher events) {
        this.claude = claude;
        this.homework = homework;
        this.mapper = mapper;
        this.prompts = prompts;
        this.props = props;
        this.events = events;
    }

    /** Bild, skärmdump, PDF, Word eller text → nya läxor/prov. */
    public IntakeResult intake(UploadedDocument doc, String deviceId, String hint) {
        return intake(java.util.List.of(doc), deviceId, hint);
    }

    /** Flera skärmdumpar/filer på en gång (t.ex. veckoplanens båda sidor). */
    public IntakeResult intake(java.util.List<UploadedDocument> docs, String deviceId, String hint) {
        String today = LocalDate.now(props.zone()).toString();
        ObjectNode req = claude.newRequest(props.anthropic().vision(), prompts.extraction(), "Dagens datum: " + today);

        ArrayNode content = mapper.createArrayNode();
        for (UploadedDocument doc : docs) {
            doc.addTo(content, mapper);
        }
        UploadedDocument doc = docs.get(0);
        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", hint == null || hint.isBlank()
                ? "Läs av läxorna och proven i materialet."
                : "Läs av läxorna och proven i materialet. Eleven säger: " + hint);

        ObjectNode message = req.putArray("messages").addObject();
        message.put("role", "user");
        message.set("content", content);

        return save(claude.create(req, "vision"), deviceId,
                doc.kind() == UploadedDocument.Kind.IMAGE ? Homework.Source.PHOTO : Homework.Source.MANUAL);
    }

    /** Bakåtkompatibelt för bilder. */
    public IntakeResult intake(byte[] image, String mediaType, String deviceId, String hint) {
        return intake(UploadedDocument.of(image, "bild", mediaType), deviceId, hint);
    }

    /** Inklistrad text, t.ex. från InfoMentors veckoplan eller ett veckobrev. */
    public IntakeResult intakeText(String text, String deviceId) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Ingen text");
        }
        if (text.length() > 30_000) {
            throw new IllegalArgumentException("Texten är för lång – klistra in en vecka i taget");
        }
        String today = LocalDate.now(props.zone()).toString();
        ObjectNode req = claude.newRequest(props.anthropic().vision(), prompts.extraction(), "Dagens datum: " + today);
        ObjectNode message = req.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", "Läs ut läxor och prov ur den här texten:\n\n" + text);
        return save(claude.create(req, "intake-text"), deviceId, Homework.Source.MANUAL);
    }

    private IntakeResult save(ClaudeResponse response, String deviceId, Homework.Source source) {
        HomeworkExtraction extraction = HomeworkExtraction.parse(response.text(), mapper);

        List<Homework> saved = extraction.assignmentsOrEmpty().stream()
                .filter(item -> item.title() != null && !item.title().isBlank())
                .map(item -> {
                    Homework h = homework.add(item.subject(), item.title(), item.description(),
                            item.dueDateOrNull(), item.steps(), source);
                    return homework.updateContent(h.getId(), null, null, item.kindOrDefault(), null).orElse(h);
                })
                .toList();

        if (deviceId != null && !deviceId.isBlank()) {
            events.publishEvent(new HomeworkAddedEvent(deviceId, saved));
        }
        return new IntakeResult(saved, extraction.note());
    }
}
