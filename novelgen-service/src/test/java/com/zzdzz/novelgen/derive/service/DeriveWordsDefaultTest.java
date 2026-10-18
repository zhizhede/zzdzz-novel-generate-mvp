package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.model.dto.derive.RunCreateDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** v3.7 字数短期档：wordsMin/Max 全缺省按 form 默认带宽，半传/未知形态拒绝。 */
class DeriveWordsDefaultTest {

    private static RunCreateDTO dto(Integer min, Integer max) {
        return new RunCreateDTO(min, max, List.of("冒险"), "0.75", null, "LONG", null, null, null, null, null, null);
    }

    @Test
    void bothNull_usesFormDefault() {
        assertArrayEquals(new int[]{100000, 200000}, DeriveSampleService.resolveWordBand(dto(null, null), "LONG"));
        assertArrayEquals(new int[]{10000, 30000}, DeriveSampleService.resolveWordBand(dto(null, null), "SHORT_STANDALONE"));
        assertArrayEquals(new int[]{80000, 160000}, DeriveSampleService.resolveWordBand(dto(null, null), "LIGHT_NOVEL"));
    }

    @Test
    void bothGiven_usedAsIs() {
        assertArrayEquals(new int[]{50000, 70000},
                DeriveSampleService.resolveWordBand(dto(50000, 70000), "SHORT_STANDALONE"));
    }

    @Test
    void halfGiven_rejected() {
        assertThrows(BizException.class, () -> DeriveSampleService.resolveWordBand(dto(50000, null), "LONG"));
        assertThrows(BizException.class, () -> DeriveSampleService.resolveWordBand(dto(null, 70000), "LONG"));
    }

    @Test
    void unknownForm_withoutBand_rejected() {
        assertThrows(BizException.class,
                () -> DeriveSampleService.resolveWordBand(dto(null, null), "SOME_NEW_FORM"));
    }
}
