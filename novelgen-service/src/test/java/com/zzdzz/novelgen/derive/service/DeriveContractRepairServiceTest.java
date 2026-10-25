package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.contract.ScaffoldBeat;
import com.zzdzz.novelgen.derive.contract.ScaffoldContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.vo.derive.PlanValidationVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DeriveContractRepairServiceTest {

    @Test
    void knowledgeErrorAddsPrerequisiteSyntheticBeat() {
        PlanContracts.RoutePlan plan = new PlanContracts.RoutePlan(1, List.of(), List.of(),
                List.of(), List.of());
        DeriveStateLedgerService.StateLedger ledger =
                new DeriveStateLedgerService.StateLedger(Map.of(), Set.of(),
                        List.of("第15拍39要求知识未获得：K-1"));
        ScaffoldContract scaffold = new ScaffoldContract(18, 1, List.of(),
                List.of(new ScaffoldBeat(1, 1, "事件", "开头", "x", "F-1")), List.of());

        var result = new DeriveContractRepairService()
                .repair(plan, ledger, scaffold, new PlanValidationVO(1, null, false,
                        List.of(), List.of()));

        assertTrue(result.changed());
        assertTrue(result.repairedFacts().contains("K-1"));
        assertTrue(result.plan().synthetic().stream()
                .anyMatch(s -> s.writesKnowledge().contains("K-1")));
    }
}