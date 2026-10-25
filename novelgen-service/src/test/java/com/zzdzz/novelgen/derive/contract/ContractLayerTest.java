package com.zzdzz.novelgen.derive.contract;

import com.zzdzz.novelgen.derive.service.DeriveFactService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ContractLayerTest {

    @Test
    void factKeyIsStableForSameFact() {
        assertEquals(DeriveFactService.factKey("印痕呈三重旋涡"),
                DeriveFactService.factKey("印痕呈三重旋涡"));
    }

    @Test
    void factKeyDiffersForDifferentFacts() {
        assertNotEquals(DeriveFactService.factKey("印痕呈三重旋涡"),
                DeriveFactService.factKey("少年被诱入密室"));
    }

    @Test
    void contractLayersAreDistinct() {
        assertEquals(ContractLayer.SCAFFOLD, ContractLayer.valueOf("SCAFFOLD"));
        assertEquals(ContractLayer.REVIEW, ContractLayer.valueOf("REVIEW"));
    }
}