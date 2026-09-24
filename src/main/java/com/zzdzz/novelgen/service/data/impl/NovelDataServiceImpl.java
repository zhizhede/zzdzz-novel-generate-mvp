package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.NovelMapper;
import com.zzdzz.novelgen.model.dto.NovelDTO;
import com.zzdzz.novelgen.service.data.NovelDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** novels 数据服务实现。 */
@Service
public class NovelDataServiceImpl extends ServiceImpl<NovelMapper, NovelDTO> implements NovelDataService {

    @Override
    public List<NovelDTO> listAlive() {
        return baseMapper.listAlive();
    }

    @Override
    public Long findIdByTitle(String title) {
        return baseMapper.findIdByTitle(title);
    }

    @Override
    public long insert(long userId, String title, String description, Long stylePackId, String approvalMode, String status) {
        return baseMapper.insert(userId, title, description, stylePackId, approvalMode, status);
    }

    @Override
    public int activate(long novelId) {
        return baseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<NovelDTO>()
                .eq("id", novelId)
                .eq("status", "draft")
                .eq("is_deleted", false)
                .set("status", "active"));
    }

    @Override
    public String findApprovalMode(long novelId) {
        return baseMapper.findApprovalMode(novelId);
    }

    @Override
    public int updateApprovalMode(long novelId, String mode) {
        return baseMapper.updateApprovalMode(novelId, mode);
    }

    @Override
    public String findPlanMode(long novelId) {
        return baseMapper.findPlanMode(novelId);
    }

    @Override
    public int updatePlanMode(long novelId, String mode) {
        return baseMapper.updatePlanMode(novelId, mode);
    }

    @Override
    public int chapterCount(long novelId) {
        return baseMapper.chapterCount(novelId);
    }

    @Override
    public String findDeriveConfig(long novelId) {
        return baseMapper.findDeriveConfig(novelId);
    }

    @Override
    public int updateDeriveConfig(long novelId, String deriveConfigJson) {
        return baseMapper.updateDeriveConfig(novelId, deriveConfigJson);
    }

    @Override
    public com.zzdzz.novelgen.service.data.NovelDataService.AutoStateRow findAutoState(long novelId) {
        return baseMapper.findAutoState(novelId);
    }

    @Override
    public int updateAutoState(long novelId, String state, String message) {
        return baseMapper.updateAutoState(novelId,
                state,
                message == null ? null : message.substring(0, Math.min(message.length(), 256)));
    }

    @Override
    public int bumpAutoVolumes(long novelId) {
        return baseMapper.bumpAutoVolumes(novelId);
    }

    @Override
    public int updateProfile(long novelId, String title, String description) {
        return baseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<NovelDTO>()
                .eq("id", novelId)
                .eq("is_deleted", false)
                .set("title", title)
                .set("description", description == null ? "" : description));
    }

    @Override
    public int softDelete(long novelId) {
        return baseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<NovelDTO>()
                .eq("id", novelId)
                .eq("is_deleted", false)
                .set("is_deleted", true)
                .set("delete_time", java.time.OffsetDateTime.now()));
    }
}
