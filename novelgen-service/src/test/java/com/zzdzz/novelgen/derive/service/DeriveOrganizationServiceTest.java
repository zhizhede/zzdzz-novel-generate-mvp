package com.zzdzz.novelgen.derive.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveOrganizationServiceTest {
    @Test void rejectsMissingRule() {
        var service = new DeriveOrganizationService(null);
        var o = new DeriveOrganizationService.OrganizationInput("ORG","T","组织","目标","私利","资源","","弱点","压力",List.of());
        assertTrue(service.validate(o, List.of()).contains("ORGANIZATION_RULE_MISSING"));
    }
}