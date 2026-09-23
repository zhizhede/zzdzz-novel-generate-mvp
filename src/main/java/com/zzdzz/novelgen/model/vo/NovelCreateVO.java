package com.zzdzz.novelgen.model.vo;

/** 开书入参：书名 + 品类预设（预设的指纹/门禁/规则克隆为本书私有风格包）。 */
public record NovelCreateVO(String title, String description, Long presetId) {
}
