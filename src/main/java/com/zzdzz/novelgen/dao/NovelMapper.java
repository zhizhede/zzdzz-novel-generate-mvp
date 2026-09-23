package com.zzdzz.novelgen.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.dto.NovelDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** novels 表 MyBatis-Plus Mapper：自定义 SQL 一律在 resources/mapper/NovelMapper.xml。 */
public interface NovelMapper extends BaseMapper<NovelDTO> {

    List<NovelDTO> listAlive();

    Long findIdByTitle(@Param("title") String title);

    long insert(@Param("userId") long userId, @Param("title") String title, @Param("description") String description, @Param("stylePackId") Long stylePackId, @Param("approvalMode") String approvalMode);

    String findApprovalMode(@Param("novelId") long novelId);

    int updateApprovalMode(@Param("novelId") long novelId, @Param("mode") String mode);

    String findPlanMode(@Param("novelId") long novelId);

    int updatePlanMode(@Param("novelId") long novelId, @Param("mode") String mode);

    int chapterCount(@Param("novelId") long novelId);

    String findDeriveConfig(@Param("novelId") long novelId);

    int updateDeriveConfig(@Param("novelId") long novelId, @Param("deriveConfigJson") String deriveConfigJson);

    com.zzdzz.novelgen.service.data.NovelDataService.AutoStateRow findAutoState(@Param("novelId") long novelId);

    int updateAutoState(@Param("novelId") long novelId, @Param("state") String state, @Param("message") String message);

    int bumpAutoVolumes(@Param("novelId") long novelId);
}
