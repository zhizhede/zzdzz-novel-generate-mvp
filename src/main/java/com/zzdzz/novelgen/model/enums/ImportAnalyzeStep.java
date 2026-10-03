package com.zzdzz.novelgen.model.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 导入书籍后的「解析链」步骤：**只解析，不规划**——把导入正文里已经存在的东西用 LLM 抽出来，
 * 落成素材库资产。**不规划新章**（那是规划页与生成管线，2026-10-03 用户定调）：
 * 导入书的解析不该顺手规划出一卷续写，规划是另一个入口、另一个决定。
 *
 * 顺序即执行顺序（也是依赖顺序）：事实账 → 大纲 → 素材卡 → 世界观 → 文风规则 → 向量索引 → 章纲反推。
 * 依赖说明：大纲用得上事实账；世界观用得上素材卡；**章纲反推只读本章正文**，不依赖其他步。
 */
public enum ImportAnalyzeStep {

    /** 逐章 LLM：事实账 + 世界状态快照 + 伏笔提议（digest 三个产出，一章一次调用）。 */
    DIGESTS("DIGESTS", "事实账 + 世界状态 + 伏笔提议"),
    /** 从正文/事实账合成全书大纲，写 canon(misc/大纲)——已写过则覆盖。 */
    OUTLINE("OUTLINE", "全书大纲"),
    /** 从正文摘要抽设定层素材卡（角色/物品/地点/组织/现象…），写 material_cards。 */
    CARDS("CARDS", "素材卡（角色/物品/地点…）"),
    /** 合成世界观文档，写 canon(world/世界观)。 */
    WORLD("WORLD", "世界观文档"),
    /** 文风规则提炼（语料节选 → 规则列表），写回本书风格包 rules_md。 */
    RULES("RULES", "文风规则（写回风格包）"),
    /** 事实账 + 素材卡向量化（RAG 语义检索前置；走 embedding 模型，与会话 LLM 分开接入）。 */
    EMBEDDINGS("EMBEDDINGS", "向量索引（RAG 召回前置）"),
    /**
     * 从**已有正文**反推章纲：把成稿章按实际分场拆成场景（写 outline_yaml + chapter_scenes），
     * 是「这章实际怎么写的」的事后描述，**不是**写作规划——不动正文、章状态与门禁报告。
     * 单章几十秒，故只取前若干章（见 ImportAnalyzeService.DERIVE_OUTLINE_CHAPTER_CAP）。
     * 与已移除的旧 `CHAPTER_OUTLINES`（把新规划卷入库生成队列）是两回事，故用新键。
     */
    DERIVE_CHAPTER_OUTLINES("DERIVE_CHAPTER_OUTLINES", "章纲（从正文反推场景拆解）");

    private final String wire;
    private final String label;

    ImportAnalyzeStep(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    public String wire() {
        return wire;
    }

    public String label() {
        return label;
    }

    /** 全步骤（默认全勾）按执行顺序。 */
    public static List<ImportAnalyzeStep> all() {
        return List.of(values());
    }

    /** 勾选值 → 步骤；未知/空值返回 null（不认识的键忽略，不报错）。 */
    public static ImportAnalyzeStep of(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(s -> s.wire.equals(v) || s.name().equals(v)).findFirst().orElse(null);
    }

    /** 勾选键集合 → 按执行顺序排好的步骤列表；空/全未知 → 空列表（＝不解析）。 */
    public static List<ImportAnalyzeStep> ordered(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return all().stream().filter(s -> keys.stream().anyMatch(k -> s == of(k))).toList();
    }
}
