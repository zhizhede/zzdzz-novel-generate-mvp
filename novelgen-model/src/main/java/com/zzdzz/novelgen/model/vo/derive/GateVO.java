package com.zzdzz.novelgen.model.vo.derive;

import java.util.List;

/** P6 立项闸结果（verdict：GO 放行 / REWORK 回对应规划层重做 / REJECT 熔断；round=第几次评审）。 */
public record GateVO(
        long runId,
        long novelId,
        String verdict,
        String gateStatus,
        int round,
        List<String> issues,
        String directive,
        String repairTarget,
        List<GateCriterionVO> criteria
) {
    public GateVO(long runId, long novelId, String verdict, String gateStatus,
                  int round, List<String> issues, String directive, String repairTarget) {
        this(runId, novelId, verdict, gateStatus, round, issues, directive,
                repairTarget, List.of());
    }

    public GateVO(long runId, long novelId, String verdict, String gateStatus,
                  int round, List<String> issues, String directive) {
        this(runId, novelId, verdict, gateStatus, round, issues, directive, "NONE", List.of());
    }
}
