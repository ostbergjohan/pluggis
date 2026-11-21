package se.ostberg.pluggis.device;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DeviceWebSocketHandler handler;

    public WebSocketConfig(DeviceWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Enheten är ingen webbläsare; origin-kontroll ger inget skydd här – token gör det.
        registry.addHandler(handler, "/ws/device").setAllowedOrigins("*");
    }

    /** Ett yttrande på ~15 s wav (16 kHz mono) är ca 0,5 MB – ge marginal. */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(4 * 1024 * 1024);
        container.setMaxTextMessageBufferSize(256 * 1024);
        container.setMaxSessionIdleTimeout(15 * 60 * 1000L);
        return container;
    }
}
