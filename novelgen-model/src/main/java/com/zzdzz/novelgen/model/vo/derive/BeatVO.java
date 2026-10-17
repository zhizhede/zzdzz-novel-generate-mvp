package com.zzdzz.novelgen.model.vo.derive;

import java.math.BigDecimal;

/** 拍实例进度行（seq=全局拍序；filledText=⑥b 填实后的具体情节点）。 */
public record BeatVO(
        int seq,
        String beatType,
        BigDecimal pacing,
        String source,
        Integer chapterNo,
        String status,
        String filledText
) {
}
