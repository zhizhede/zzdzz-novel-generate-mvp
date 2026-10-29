package com.zzdzz.novelgen.derive.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveWorldInstitutionServiceTest {
    @Test void rejectsMissingEnforcement() {
        var service = new DeriveWorldInstitutionService(null);
        var i = new DeriveWorldInstitutionService.InstitutionInput("INST","SOCIAL","制度","法则","","","代价",List.of("C1"));
        assertTrue(service.validate(i, List.of("C1")).contains("INSTITUTION_ENFORCEMENT_MISSING"));
    }
}