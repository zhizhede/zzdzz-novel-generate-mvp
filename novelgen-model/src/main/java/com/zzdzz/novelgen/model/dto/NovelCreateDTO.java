package com.zzdzz.novelgen.model.dto;

/**
 * 开书入参：书名 + 品类预设 + 可选的通用生成配置。
 * deriveConfig 可空——不传时按默认参数开书。
 */
public record NovelCreateDTO(String title, String description, Long presetId,
                            DeriveConfigVO deriveConfig, Boolean draft, Long novelId) {

    /** 通用生成参数：掺水量 0-100（50 均衡）/POV/主视角/节奏说明/每卷章数/总目标章数/无人续跑/优先级 0-2/类型标签。 */
    public record DeriveConfigVO(Integer water, String pov, String povCharacter, String pacingNote,
                                 Integer chaptersPerVolume, Integer targetChapters, Boolean autoContinue,
                                 Integer priority, java.util.List<String> tags) {
    }
}
