package com.zzdzz.novelgen.model.vo.derive;

import java.util.List;

/** P6 立项闸结果（verdict：GO 放行 / REWORK 回⑥b重做 / REJECT 熔断；round=第几次评审）。 */
public record GateVO(
        long runId,
        long novelId,
        String verdict,
        String gateStatus,
        int round,
        List<String> issues,
        String directive
) {
}
