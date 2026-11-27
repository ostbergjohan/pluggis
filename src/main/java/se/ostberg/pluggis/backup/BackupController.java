package se.ostberg.pluggis.backup;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Säkerhetskopior i föräldravyn (bara föräldern – elevkoden når inte hit). */
@RestController
@RequestMapping("/api/backups")
public class BackupController {

    private final BackupService backups;

    public BackupController(BackupService backups) {
        this.backups = backups;
    }

    public record Settings(Boolean nightly) {
    }

    @GetMapping
    public Map<String, Object> list() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supported", backups.supported());
        m.put("nightly", backups.nightly());
        m.put("keep", backups.keep());
        m.put("dir", backups.dir().toString());
        m.put("backups", backups.list());
        return m;
    }

    @PostMapping
    public ResponseEntity<?> create() {
        try {
            backups.create();
            return ResponseEntity.ok(list());
        } catch (IllegalStateException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } catch (IOException | RuntimeException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Kopian misslyckades: " + e.getMessage()));
        }
    }

    @PutMapping
    public Map<String, Object> settings(@RequestBody Settings body) {
        if (body.nightly() != null) {
            backups.setNightly(body.nightly());
        }
        return list();
    }

    @GetMapping("/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        var file = backups.file(name);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(new FileSystemResource(file));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String name) throws IOException {
        return backups.delete(name) ? ResponseEntity.ok(list()) : ResponseEntity.notFound().build();
    }
}
