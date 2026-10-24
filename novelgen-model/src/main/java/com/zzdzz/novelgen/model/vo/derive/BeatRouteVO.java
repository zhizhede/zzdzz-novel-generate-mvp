package com.zzdzz.novelgen.model.vo.derive;

import java.util.List;

/** 单卷章功能槽与拍路由结果。 */
public record BeatRouteVO(
        long runId,
        int volumeNo,
        int fromChapter,
        int toChapter,
        int chapterCount,
        int routedBeats,
        int syntheticBeats,
        int skippedBeats,
        List<String> validationWarnings,
        boolean allDone) {
}
