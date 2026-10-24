package com.zzdzz.novelgen.model.vo.derive;

import java.util.List;

/** ⑧质检结果（一轮）。 */
public record QcVO(
        long runId,
        long novelId,
        int roundNo,
        String verdict,
        int scriptPassed,
        int scriptTotal,
        boolean llmRan,
        boolean llmPassed,
        List<String> issues,
        Integer wordsActual,
        Integer chapterAvgActual,
        Integer pActual,
        String repairTarget
) {
    public QcVO(long runId, long novelId, int roundNo, String verdict,
                int scriptPassed, int scriptTotal, boolean llmRan, boolean llmPassed,
                List<String> issues, Integer wordsActual, Integer chapterAvgActual,
                Integer pActual) {
        this(runId, novelId, roundNo, verdict, scriptPassed, scriptTotal,
                llmRan, llmPassed, issues, wordsActual, chapterAvgActual, pActual, "NONE");
    }
}
