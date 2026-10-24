package com.zzdzz.novelgen.model.vo.derive;

/** run 的卷级规划读模型。 */
public record VolumePlanVO(
        int volumeNo,
        int fromChapter,
        int toChapter,
        String arc,
        String localEvent,
        String localClosure,
        String nextHook,
        String globalRole,
        String status
) {
}
