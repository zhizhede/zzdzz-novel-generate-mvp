package com.zzdzz.novelgen.derive.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveGenreCapabilityServiceTest {
    @Test void worldAlwaysEnabledAndMysteryDetected() {
        var service = new DeriveGenreCapabilityService(null);
        var caps = service.resolve(List.of("悬疑"), "案件", "闭合");
        assertTrue(caps.contains(DeriveGenreCapabilityService.Capability.WORLD));
        assertTrue(caps.contains(DeriveGenreCapabilityService.Capability.MYSTERY));
    }
}