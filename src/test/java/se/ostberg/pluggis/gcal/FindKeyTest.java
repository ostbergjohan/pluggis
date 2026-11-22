package se.ostberg.pluggis.gcal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FindKeyTest {

    @Test
    void findsServiceAccountKeyWhateverItIsCalled(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("README.md"), "hej");
        Files.writeString(dir.resolve("annat.json"), "{\"type\":\"authorized_user\"}");
        Path key = dir.resolve("homeassistant-calender-442218-1dc6cd40eddf.json");
        Files.writeString(key, "{\"type\":\"service_account\",\"private_key\":\"x\",\"client_email\":\"a@b\"}");
        assertThat(GoogleCalendarClient.findServiceAccountKey(dir)).isEqualTo(key);
        assertThat(GoogleCalendarClient.findServiceAccountKey(dir.resolve("finns-inte"))).isNull();
    }
}
