package com.zzdzz.novelgen.derive.contract;

/** 系统生成的稳定拍骨架；模型不得修改 tempSeq/chapterNo。 */
public record ScaffoldBeat(
        int tempSeq,
        int chapterNo,
        String beatType,
        String arcSlot,
        String sourceLabel,
        String factKey) {
}