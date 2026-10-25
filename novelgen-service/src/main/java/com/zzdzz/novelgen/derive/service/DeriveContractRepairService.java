package com.zzdzz.novelgen.derive.service;

import com.zzdzz.novelgen.derive.contract.ScaffoldContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import com.zzdzz.novelgen.model.vo.derive.PlanValidationVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 局部合同修复：优先补结构拍、知识前置和线程兑现，不做全书清空。 */
@Service
public class DeriveContractRepairService {

    public RepairResult repair(PlanContracts.RoutePlan plan,
                               DeriveStateLedgerService.StateLedger ledger,
                               ScaffoldContract scaffold,
                               PlanValidationVO issues) {
        List<PlanContracts.SyntheticAssignment> synthetic = new ArrayList<>(plan.synthetic());
        Set<String> addedFacts = new LinkedHashSet<>();
        for (String error : ledger.errors()) {
            int chapter = chapterFromError(error);
            if (chapter < 0) {
                continue;
            }
            String fact = factFromError(error);
            if (fact == null || addedFacts.contains(fact)) {
                continue;
            }
            synthetic.add(new PlanContracts.SyntheticAssignment(
                    chapter, PlanContracts.StoryLayer.LOCAL_CASE,
                    PlanContracts.BeatRole.MAIN_ADVANCE, "事件",
                    "补齐知识前置：" + fact, List.of("C1"), List.of(),
                    PlanContracts.AppearanceMode.LIVE, List.of(), "",
                    List.of(), List.of(fact), java.util.Map.of(), java.util.Map.of()));
            addedFacts.add(fact);
        }
        if (addedFacts.isEmpty()) {
            return new RepairResult(plan, List.of(), false);
        }
        PlanContracts.RoutePlan repaired = new PlanContracts.RoutePlan(
                plan.volumeNo(), plan.chapters(), plan.assignments(), synthetic, plan.skipped());
        return new RepairResult(repaired, new ArrayList<>(addedFacts), true);
    }

    private static int chapterFromError(String error) {
        if (error == null || !error.startsWith("第")) {
            return -1;
        }
        int end = error.indexOf("拍");
        try {
            return Integer.parseInt(error.substring(1, end));
        } catch (Exception e) {
            return -1;
        }
    }

    private static String factFromError(String error) {
        if (error == null) {
            return null;
        }
        int idx = error.indexOf("：");
        return idx < 0 || idx == error.length() - 1 ? null : error.substring(idx + 1);
    }

    public record RepairResult(PlanContracts.RoutePlan plan, List<String> repairedFacts,
                               boolean changed) {
    }
}