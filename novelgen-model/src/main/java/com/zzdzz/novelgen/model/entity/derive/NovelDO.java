package com.zzdzz.novelgen.model.entity.derive;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * novels · 书（源书+成品统一，v1.2 §3.1）。
 *
 * <p>jsonb 列（categoryTags/storySettings/storyAssets）**禁走 MP save()**——MP 绑 varchar、
 * PostgreSQL 拒绝隐式转（AGENTS 已知坑 #13）；一律走 {@code NovelMapper.insertFull}（XML 显式 ::jsonb）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("novels")
public class NovelDO extends DeriveBaseDO {

    /** 书名（①录入 / ⑥b起名） */
    private String title;

    /** 来源类型：SOURCE=源书 / GENERATED=系统生成 */
    private String sourceType;

    /** 类别标签 {言情:1,校园:1,…}（jsonb；源书=①标注，成品=⑤param_tags 回填） */
    private String categoryTags;

    /** 全书字数（⑧汇总回填） */
    private Integer charCount;

    /** 产品形态：LONG / SHORT_STANDALONE / LIGHT_NOVEL / LONG_INNER_SHORT / LN_INNER_SHORT */
    private String form;

    /** 章节数（⑧回填） */
    private Integer chapterCount;

    /** 书籍作者（源书=原作者；成品=系统生成） */
    private String author;

    /** 书籍平台（晋江/起点/名著/系统生成…） */
    private String platform;

    /** 全书一句话概括（⑥b） */
    private String summary;

    /** 生成它的运行 run.id（成品回链，源书为 NULL） */
    private Long runId;

    /** jsonb 留缝：实例化世界观 {"v":1,"worldview":[…],"rules":[…]}（架构附录 A-5 提表触发） */
    private String storySettings;

    /** jsonb 留缝：角色/场景/道具/关系 {"v":1,"characters":[…],…}（架构附录 A-3/A-4） */
    private String storyAssets;
}
