package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.ForeshadowDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** foreshadows 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/ForeshadowMapper.xml。 */
public interface ForeshadowMapper extends BaseMapper<ForeshadowDTO> {

    List<ForeshadowDTO> listByNovel(@Param("novelId") long novelId);

    boolean exists(@Param("novelId") long novelId, @Param("code") String code);

    int insert(@Param("novelId") long novelId, @Param("code") String code, @Param("content") String content, @Param("plantedIn") int plantedIn, @Param("recoveredIn") int recoveredIn);

    int update(@Param("id") long id, @Param("content") String content, @Param("plantedIn") Integer plantedIn, @Param("recoveredIn") Integer recoveredIn, @Param("status") String status);

    ForeshadowDTO findByCode(@Param("novelId") long novelId, @Param("code") String code);

    ForeshadowDTO findById(@Param("id") long id);

    int insertProposal(@Param("novelId") long novelId, @Param("code") String code, @Param("content") String content, @Param("proposedIn") int proposedIn);

    boolean contentExists(@Param("novelId") long novelId, @Param("content") String content);

    String nextCode(@Param("novelId") long novelId);

    int insertPlanned(@Param("novelId") long novelId, @Param("code") String code, @Param("content") String content, @Param("plantedIn") int plantedIn);

    List<String> findDirectives(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo);

    int promoteProposal(@Param("id") long id, @Param("plantedIn") int plantedIn);

    int scheduleRecovery(@Param("id") long id, @Param("recoveredIn") int recoveredIn);

    int markPlanted(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo);

    int markRecovered(@Param("novelId") long novelId, @Param("chapterNo") int chapterNo);
}
