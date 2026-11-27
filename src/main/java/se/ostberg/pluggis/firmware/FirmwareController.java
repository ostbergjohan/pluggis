package se.ostberg.pluggis.firmware;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Tab5-firmware över WiFi. Tab5 (med elevkod) får se status, leta efter en ny version och ladda ner den;
 * bara föräldern får ladda upp en egen fil.
 */
@RestController
@RequestMapping("/api/firmware")
public class FirmwareController {

    private final FirmwareService firmware;

    public FirmwareController(FirmwareService firmware) {
        this.firmware = firmware;
    }

    @GetMapping
    public Map<String, Object> status() {
        return firmware.status();
    }

    /** Leta efter en ny version (GitHub-releasen). */
    @PostMapping("/check")
    public Map<String, Object> check() {
        return firmware.check();
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download() throws IOException {
        var info = firmware.current().orElse(null);
        if (info == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] bin = firmware.binary();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bin.length)
                .header("X-Firmware-Version", info.version())
                .header("X-Firmware-MD5", info.md5())
                .body(bin);
    }

    /** Egen firmware.bin från föräldravyn (t.ex. byggd lokalt). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "version", required = false) String version) throws IOException {
        try {
            firmware.save(file.getBytes(), version, "uppladdad");
            return ResponseEntity.ok(firmware.status());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        }
    }
}
