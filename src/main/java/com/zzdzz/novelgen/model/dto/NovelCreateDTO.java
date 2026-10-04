package com.zzdzz.novelgen.model.dto;

/**
 * 开书入参：书名 + 品类预设 + 可选的样本资产克隆与衍生配置（P2）。
 * cloneAssets/deriveConfig 均可空——不选样本时就是纯预设开书，行为与旧口径一致。
 */
public record NovelCreateDTO(String title, String description, Long presetId,
                            Long sampleId, CloneAssetsVO cloneAssets, DeriveConfigVO deriveConfig,
                            Boolean draft, Long novelId) {

    /** 克隆哪些样本资产：素材卡（★2+）/世界观文档/剧情骨架预填大纲。 */
    public record CloneAssetsVO(Boolean cards, Boolean world, Boolean plotOutline) {
    }

    /** 衍生参数：掺水量 0-100（50 均衡）/POV/主视角/节奏说明/每卷章数/总目标章数/无人续跑/优先级 0-2/
     * 类型标签（沿用样本或自定，如「言情」「剑与魔法」「长篇」——控制衍生书的类型基调与标志性元素）/
     * 开书模式 mode（ORIGINAL 原创衍生=默认；MIGRATE 剧情迁移=克隆人物卡与样本逐章剧情、跳过原创性审校）/
     * protagonistFrom（MIGRATE 专用：样本里要被替换掉的原书主角名，迁入文本中全部换成 povCharacter；
     * 留空＝不改名。**刻意不做自动推断**——样本深度解析未必给主角建卡，猜错会把配角名静默改掉）。 */
    public record DeriveConfigVO(Integer water, String pov, String povCharacter, String pacingNote,
                                 Integer chaptersPerVolume, Integer targetChapters, Boolean autoContinue,
                                 Integer priority, java.util.List<String> tags, String mode,
                                 String protagonistFrom) {
    }
}
