package com.zzdzz.novelgen.model.vo;

import java.util.List;

/** 账本健康度（四层记忆体检）：伏笔账时效 + 事实账/世界状态覆盖进度；空列表表示该项健康。 */
public record LedgerHealthVO(int currentChapter,
                             int proposedCount, String oldestProposed, int oldestProposedAge,
                             List<String> plantOverdue, List<String> recoverOverdue, int archivedCount,
                             int digestCount, int digestLatestChapter,
                             int worldStateCount, int worldStateLatestChapter) {
}
