package com.zzdzz.novelgen.derive.contract;

import java.util.List;

/**
 * 章级日程的一行（章节 → 本章必埋/必兑的线索 key + 是否揭示章）。
 *
 * <p>它是冻结合同的机械投影，不是故事事实的第二个真源：{@link ChapterScheduleProjector}
 * 只能由合同推导，任何节点都不许手写或改写。
 */
public record ChapterScheduleEntry(
        int chapterNo,
        int volumeNo,
        boolean reveal,
        List<String> mustPlant,
        List<String> mustPayoff) {
}
