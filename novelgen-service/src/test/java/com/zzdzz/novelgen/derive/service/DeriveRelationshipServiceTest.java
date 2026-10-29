package com.zzdzz.novelgen.derive.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveRelationshipServiceTest {
    @Test void rejectsOutOfRange() {
        var service = new DeriveRelationshipService(null);
        var r = new DeriveRelationshipService.RelationshipInput("C1","C2","ALLY","公开","隐藏","",101,0,0,"历史","转折","","{}","{}");
        assertTrue(service.validate(r, List.of("C1","C2"), false, true).contains("RELATIONSHIP_VALUE_OUT_OF_RANGE"));
    }
}