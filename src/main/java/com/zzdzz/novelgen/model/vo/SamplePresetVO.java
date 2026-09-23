package com.zzdzz.novelgen.model.vo;

/** 开书向导·由导入小说一键建品类预设的结果：新预设 id + 品类与语料落库位置。 */
public record SamplePresetVO(long presetId, String presetName, String genre, int chunks) {
}
