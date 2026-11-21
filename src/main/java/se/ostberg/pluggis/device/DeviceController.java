package se.ostberg.pluggis.device;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/** Vilka enheter som är anslutna just nu – används av webbgränssnittet. */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceRegistry registry;

    public DeviceController(DeviceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public Map<String, Set<String>> connected() {
        return Map.of("connected", registry.connected());
    }
}
