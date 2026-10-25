package com.zzdzz.novelgen.derive.contract;

import java.util.List;

/** 系统生成的结构骨架；模型只能填充创作字段。 */
public record ScaffoldContract(
        int chapterCount,
        int volumeCount,
        List<VolumeRange> volumeRanges,
        List<ScaffoldBeat> beats,
        List<KnowledgeFact> facts) {

    public record VolumeRange(int volumeNo, int fromChapter, int toChapter) {
    }
}