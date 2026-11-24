package se.ostberg.pluggis.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Adresser: {@code /} är elevchatten (samma som {@code /chat.html}, som finns kvar), {@code /admin} är föräldravyn.
 * {@code /index.html} pekar vidare till {@code /admin} så att gamla bokmärken till föräldravyn fungerar.
 */
@Configuration
public class WebRoutes implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/chat.html");
        registry.addViewController("/admin").setViewName("forward:/admin.html");
        registry.addRedirectViewController("/admin/", "/admin");
        registry.addRedirectViewController("/index.html", "/admin");
    }
}
