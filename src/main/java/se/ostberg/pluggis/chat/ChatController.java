package se.ostberg.pluggis.chat;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Elevlänken. {@code /me} nås med elevens token, resten bara med föräldrarnas. */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final String DEFAULT_DEVICE = "tab5-1";

    private final ChatAccess access;
    private final se.ostberg.pluggis.settings.StudentProfile student;
    private final se.ostberg.pluggis.speech.SpeechClient speech;
    private final se.ostberg.pluggis.student.StudentService students;

    public ChatController(ChatAccess access, se.ostberg.pluggis.settings.StudentProfile student,
                          se.ostberg.pluggis.speech.SpeechClient speech, se.ostberg.pluggis.student.StudentService students) {
        this.students = students;
        this.access = access;
        this.student = student;
        this.speech = speech;
    }

    /** Vad elevchatten behöver veta vid start. */
    @GetMapping("/me")
    public Map<String, Object> me() {
        var me = students.current();
        return Map.of("deviceId", me.getDeviceId(), "name", me.getName(), "studentId", me.getId(),
                "serverVoice", speech.ttsEnabled());
    }

    @GetMapping("/link")
    public Map<String, Object> link() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", access.token().isPresent());
        access.token().ifPresent(t -> m.put("path", "/chat.html#t=" + t));
        m.put("deviceId", access.deviceId(DEFAULT_DEVICE));
        return m;
    }

    /** Skapar (eller byter) elevlänken. */
    @PostMapping("/link")
    public Map<String, Object> rotate() {
        access.rotate();
        return link();
    }

    @PutMapping("/link")
    public Map<String, Object> settings(@RequestBody Map<String, String> body) {
        if (body.containsKey("deviceId")) {
            access.setDeviceId(body.get("deviceId"));
        }
        return link();
    }

    @DeleteMapping("/link")
    public Map<String, Object> disable() {
        access.disable();
        return link();
    }
}
