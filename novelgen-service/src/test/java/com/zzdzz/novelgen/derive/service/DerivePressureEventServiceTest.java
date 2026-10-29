package com.zzdzz.novelgen.derive.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DerivePressureEventServiceTest {
    @Test void rejectsHiddenPressureMissing() {
        var service = new DerivePressureEventService(null);
        var e = new DerivePressureEventService.PressureEventInput("EVT","T","事件","C1",List.of("C2"),null,null,"表面","","{}","{}","{}","{}",1,1);
        assertTrue(service.validate(e, List.of("C1","C2"), List.of(), List.of()).contains("PRESSURE_EVENT_HIDDEN_PRESSURE_MISSING"));
    }
}