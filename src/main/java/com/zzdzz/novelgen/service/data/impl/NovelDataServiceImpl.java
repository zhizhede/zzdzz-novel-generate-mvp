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
    public long insert(long userId, String title, String description, Long stylePackId, String approvalMode) {
        return baseMapper.insert(userId, title, description, stylePackId, approvalMode);
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
}
