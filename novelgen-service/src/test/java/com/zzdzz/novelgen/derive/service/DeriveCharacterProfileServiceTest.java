package com.zzdzz.novelgen.derive.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveCharacterProfileServiceTest {
    @Test void rejectsLabelOnly() {
        var service = new DeriveCharacterProfileService(null);
        var p = new DeriveCharacterProfileService.ProfileInput("C1","偶像","偶像","偶像","偶像","","偶像","","","","","","START");
        assertTrue(service.validate(p, List.of(), List.of()).contains("CHARACTER_LABEL_ONLY"));
    }
}