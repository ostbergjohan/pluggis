package se.ostberg.pluggis.firmware;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirmwareServiceTest {

    @TempDir
    Path dir;

    private FirmwareService service() {
        return new FirmwareService(dir.toString(), "x/y", "tab5", "", new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    private static byte[] image(int size) {
        byte[] b = new byte[size];
        b[0] = (byte) 0xE9;
        return b;
    }

    @Test
    void sparasOchVisas() throws Exception {
        FirmwareService fw = service();
        assertThat(fw.current()).isEmpty();
        assertThat(fw.status()).containsEntry("available", false);
        fw.save(image(4096), "2026.11.27-abc1234", "uppladdad");
        assertThat(fw.current()).get().extracting(FirmwareService.Info::version).isEqualTo("2026.11.27-abc1234");
        assertThat(fw.status()).containsEntry("available", true).containsEntry("size", 4096L);
        assertThat(fw.binary()).hasSize(4096);
        assertThat(service().current()).isPresent();   // finns kvar efter omstart
    }

    @Test
    void baraRiktigFirmware() {
        FirmwareService fw = service();
        assertThatThrownBy(() -> fw.save(new byte[4096], "v", "x")).hasMessageContaining("ESP32");
        assertThatThrownBy(() -> fw.save(image(10), "v", "x")).hasMessageContaining("liten");
        assertThatThrownBy(() -> fw.save(image((int) FirmwareService.MAX_SIZE + 1), "v", "x")).hasMessageContaining("stor");
    }
}
