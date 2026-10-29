package com.zzdzz.novelgen.derive.orchestration;

/** 单阶段 handler 的确定性结果。 */
public record StageOutcome(
        boolean done,
        Integer outputRevision,
        String errorCode,
        String errorPath,
        String errorMessage,
        int chaptersDegraded,
        String completionMessage) {

    public static final String DRAFT_FAILED = "STAGE_HANDLER_FAILED";

    public static StageOutcome done(Integer outputRevision) {
        return new StageOutcome(true, outputRevision, null, null, null, 0, null);
    }

    public static StageOutcome done(Integer outputRevision, int chaptersDegraded,
                                    String completionMessage) {
        return new StageOutcome(true, outputRevision, null, null, null,
                chaptersDegraded, completionMessage);
    }

    public static StageOutcome failed(String code, String path, String message) {
        return new StageOutcome(false, null, code, path, message, 0, null);
    }
}
