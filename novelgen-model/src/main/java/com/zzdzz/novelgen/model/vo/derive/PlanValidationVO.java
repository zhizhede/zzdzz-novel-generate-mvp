package com.zzdzz.novelgen.model.vo.derive;

import java.util.List;

/** 故事规划校验结果。 */
public record PlanValidationVO(
        long runId,
        Integer volumeNo,
        boolean passed,
        List<Issue> hardErrors,
        List<Issue> warnings) {

    /** 单条规划问题。 */
    public record Issue(
            String level,
            String code,
            String repairTarget,
            Integer volumeNo,
            Integer chapterNo,
            List<Integer> seqs,
            String message) {
    }
}
