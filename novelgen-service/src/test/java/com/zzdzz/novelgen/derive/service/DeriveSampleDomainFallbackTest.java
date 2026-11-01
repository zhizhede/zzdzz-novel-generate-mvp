package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.model.entity.derive.DomainCellDO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveSampleDomainFallbackTest {

    @Test
    void genericFallbackSupportsAnyGenreWithoutTypeSpecificBranch() {
        DomainCellDO cell = DeriveSampleService.genericFallbackCell(
                "校园", "FANTASY", "魔法学院里的成长故事");

        assertEquals("FANTASY·通用域", cell.getName());
        assertTrue(cell.getCats().contains("校园"));
        assertTrue(cell.getRelationNote().contains("创作意图"));
    }
}
