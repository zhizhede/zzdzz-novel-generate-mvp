package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.NovelDO;
import org.apache.ibatis.annotations.Param;

/**
 * novels（衍生域）MyBatis-Plus Mapper。
 *
 * <p>⚠ jsonb 三列（category_tags/story_settings/story_assets）**禁走 MP save()/updateById**——
 * MP 绑 varchar、PG 拒绝隐式转（AGENTS 坑 #13）。写入一律 {@link #insertFull}（XML 显式 ::jsonb）。
 * 自定义 SQL 全在 resources/mapper/DeriveNovelMapper.xml（文件名带 Derive 前缀避开 zzdzz NovelMapper.xml）。
 */
public interface NovelMapper extends BaseMapper<NovelDO> {

    /** 业务列全量插入（jsonb 显式 cast，created_* 走库默认），返回新 id。 */
    long insertFull(@Param("p") NovelDO novel);

    /**
     * 元数据更新（⑥b 起名/摘要）。
     * ⚠ 禁走 MP updateById：selectById 读回的 jsonb 列非空 → NOT_NULL 策略会把它们绑成 varchar 写回被 PG 拒。
     */
    int updateMeta(@Param("id") long id, @Param("title") String title, @Param("summary") String summary);

    /** ⑥b 落主角名（story_assets.protagonist，jsonb 留缝；⑧ QC 读它做指纹登记与查重）。 */
    int updateProtagonist(@Param("id") long id, @Param("protagonist") String protagonist);

    /** ⑥b 落主角名+全书 cast 表（story_assets；⑦ 每章注入 cast 防称谓漂移）。 */
    int updateCast(@Param("id") long id, @Param("protagonist") String protagonist,
                   @Param("cast") String castJson);

    /** ⑧ 统计回填（char_count / chapter_count）。 */
    int updateStats(@Param("id") long id, @Param("charCount") int charCount,
                    @Param("chapterCount") int chapterCount);
}
