package se.ostberg.laxcoach.homework;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import se.ostberg.laxcoach.claude.ClaudeClient;
import se.ostberg.laxcoach.claude.ClaudeResponse;
import se.ostberg.laxcoach.config.LaxcoachProperties;
import se.ostberg.laxcoach.support.Prompts;

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
    private final LaxcoachProperties props;
    private final ApplicationEventPublisher events;

    public PhotoIntakeService(ClaudeClient claude, HomeworkService homework, ObjectMapper mapper,
                              Prompts prompts, LaxcoachProperties props, ApplicationEventPublisher events) {
        this.claude = claude;
        this.homework = homework;
        this.mapper = mapper;
        this.prompts = prompts;
        this.props = props;
        this.events = events;
    }

    public IntakeResult intake(byte[] image, String mediaType, String deviceId, String hint) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("Ingen bild");
        }
        if (image.length > MAX_BYTES) {
            throw new IllegalArgumentException("Bilden är för stor (max 5 MB) – sänk upplösning/kvalitet");
        }
        if (mediaType == null || !MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("Bildformatet stöds inte: " + mediaType);
        }

        String today = LocalDate.now(props.zone()).toString();
        ObjectNode req = claude.newRequest(
                props.anthropic().visionModel(), 2048, prompts.extraction().replace("{{today}}", today));

        ArrayNode content = mapper.createArrayNode();
        ObjectNode imageBlock = content.addObject();
        imageBlock.put("type", "image");
        ObjectNode source = imageBlock.putObject("source");
        source.put("type", "base64");
        source.put("media_type", mediaType);
        source.put("data", Base64.getEncoder().encodeToString(image));
        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", hint == null || hint.isBlank()
                ? "Läs av uppgifterna på bilden."
                : "Läs av uppgifterna på bilden. Eleven säger: " + hint);

        ObjectNode message = req.putArray("messages").addObject();
        message.put("role", "user");
        message.set("content", content);

        return save(claude.create(req, "vision"), deviceId, Homework.Source.PHOTO);
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
        ObjectNode req = claude.newRequest(
                props.anthropic().model(), 2048, prompts.extraction().replace("{{today}}", today));
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
