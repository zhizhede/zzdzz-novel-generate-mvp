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
}
