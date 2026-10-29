package com.zzdzz.novelgen.derive.plan.compiler;

import com.zzdzz.novelgen.derive.contract.ChapterScheduleEntry;
import com.zzdzz.novelgen.derive.contract.ScaffoldContract;
import com.zzdzz.novelgen.derive.contract.StoryContract;
import com.zzdzz.novelgen.derive.plan.PlanContracts;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** 计划编译的全部确定性输入；不含正文，也不允许调用方注入模型自由结构。 */
public record PlanCompileInput(
        long runId,
        int contractVersion,
        String contractHash,
        PlanContracts.ScopeContract scope,
        StoryContract contract,
        ScaffoldContract scaffold,
        List<ChapterScheduleEntry> schedule,
        List<PlanGraphRecords.BeatCandidate> candidates,
        List<PlanContracts.VolumeContract> volumes,
        List<PlanContracts.CharacterContract> characters,
        BigDecimal defaultPace) {

    public PlanCompileInput {
        if (runId <= 0 || contractVersion <= 0) {
            throw new IllegalArgumentException("runId/contractVersion must be positive");
        }
        Objects.requireNonNull(contractHash, "contractHash");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(scaffold, "scaffold");
        schedule = copy(schedule);
        candidates = copy(candidates);
        volumes = copy(volumes);
        characters = copy(characters);
        defaultPace = defaultPace == null ? new BigDecimal("0.50") : defaultPace;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
