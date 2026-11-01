package com.zzdzz.novelgen.derive.story;

/**
 * 全书字数预算护栏：按 V3 在窗口阶段拦截当前超支、预计超支与最终窗口预计不足。
 */
public final class WordBudgetGuard {

    private WordBudgetGuard() {
    }

    /** 预算判定结果；code=OK 表示允许继续，ADVISORY 表示记录但不拦截。 */
    public record BudgetDecision(String code, String repairTarget,
                                 int projectedMin, int projectedMax) {

        public boolean ok() {
            return "OK".equals(code) || "ADVISORY".equals(severity());
        }

        public String severity() {
            if ("OK".equals(code)) {
                return "OK";
            }
            if ("BUDGET_PROJECTED_UNDERSHOOT".equals(code)) {
                return "ADVISORY";
            }
            return "HARD";
        }
    }

    /** 按当前总字数与剩余章均预算估算全书落点。 */
    public static BudgetDecision check(int totalCharCount, int windowCharCount,
                                       int remainingChapters, int chapterAvg,
                                       int wordsMin, int wordsMax) {
        int safeRemaining = Math.max(0, remainingChapters);
        int safeAvg = Math.max(0, chapterAvg);
        int projectedMin = totalCharCount
                + (int) Math.floor(safeRemaining * safeAvg * 0.9);
        int projectedMax = totalCharCount
                + (int) Math.ceil(safeRemaining * safeAvg * 1.1);
        if (totalCharCount > wordsMax) {
            return new BudgetDecision("BUDGET_WINDOW_OVERSHOOT", "COMPRESS_WINDOW",
                    projectedMin, projectedMax);
        }
        if (projectedMax > wordsMax) {
            return new BudgetDecision("BUDGET_PROJECTED_OVERSHOOT",
                    "COMPRESS_REMAINING_WINDOWS", projectedMin, projectedMax);
        }
        if (projectedMax < wordsMin) {
            if (safeRemaining > 0) {
                return new BudgetDecision("BUDGET_PROJECTED_UNDERSHOOT", "ADVISORY",
                        projectedMin, projectedMax);
            }
            return new BudgetDecision("BUDGET_FINAL_UNDERSHOOT", "EXPAND_WINDOW",
                    projectedMin, projectedMax);
        }
        return new BudgetDecision("OK", "", projectedMin, projectedMax);
    }
}