package se.ostberg.pluggis.config;

import org.junit.jupiter.api.Test;
import se.ostberg.pluggis.settings.SettingsService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAuthTest {

    private AdminAuth auth() {
        Map<String, String> store = new HashMap<>();
        SettingsService settings = mock(SettingsService.class);
        when(settings.get(anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.<String>getArgument(0))));
        doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1))).when(settings).put(anyString(), anyString());
        return new AdminAuth(settings);
    }

    @Test
    void losenordHashasOchKontrolleras() {
        AdminAuth a = auth();
        assertThat(a.passwordSet()).isFalse();
        a.setPassword("hemligt123");
        assertThat(a.passwordSet()).isTrue();
        assertThat(a.check("hemligt123")).isTrue();
        assertThat(a.check("hemligt124")).isFalse();
        assertThatThrownBy(() -> a.setPassword("kort")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cookieGallerTillsAllaLoggasUt() {
        AdminAuth a = auth();
        a.setPassword("hemligt123");
        String s = a.newSession();
        assertThat(a.valid(s)).isTrue();
        assertThat(a.validCookieHeader("x=1; " + AdminAuth.COOKIE + "=" + s)).isTrue();
        assertThat(a.valid(s + "x")).isFalse();
        assertThat(a.valid("1." + s.substring(s.indexOf('.') + 1))).isFalse();   // utgången
        a.logoutEverywhere();
        assertThat(a.valid(s)).isFalse();
    }
}
