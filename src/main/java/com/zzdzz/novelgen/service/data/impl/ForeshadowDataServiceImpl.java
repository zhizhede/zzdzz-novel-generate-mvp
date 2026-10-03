package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.ForeshadowMapper;
import com.zzdzz.novelgen.model.entity.ForeshadowDO;
import com.zzdzz.novelgen.service.data.ForeshadowDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** foreshadows 数据服务实现。 */
@Service
public class ForeshadowDataServiceImpl extends ServiceImpl<ForeshadowMapper, ForeshadowDO> implements ForeshadowDataService {

    @Override
    public List<ForeshadowDO> listByNovel(long novelId) {
        return baseMapper.listByNovel(novelId);
    }

    @Override
    public boolean exists(long novelId, String code) {
        return baseMapper.exists(novelId, code);
    }

    @Override
    public int insert(long novelId, String code, String content, int plantedIn, int recoveredIn) {
        return baseMapper.insert(novelId, code, content, plantedIn, recoveredIn);
    }

    @Override
    public int update(long id, String content, Integer plantedIn, Integer recoveredIn, String status) {
        return baseMapper.update(id, content, plantedIn, recoveredIn, status);
    }

    @Override
    public ForeshadowDO findByCode(long novelId, String code) {
        return baseMapper.findByCode(novelId, code);
    }

    @Override
    public ForeshadowDO findById(long id) {
        return baseMapper.findById(id);
    }

    @Override
    public int insertProposal(long novelId, String code, String content, int proposedIn) {
        return baseMapper.insertProposal(novelId, code, content, proposedIn);
    }

    @Override
    public boolean contentExists(long novelId, String content) {
        return baseMapper.contentExists(novelId, content);
    }

    @Override
    public String nextCode(long novelId) {
        return baseMapper.nextCode(novelId);
    }

    @Override
    public int insertPlanned(long novelId, String code, String content, int plantedIn) {
        return baseMapper.insertPlanned(novelId, code, content, plantedIn);
    }

    @Override
    public List<String> findDirectives(long novelId, int chapterNo) {
        return baseMapper.findDirectives(novelId, chapterNo);
    }

    @Override
    public int promoteProposal(long id, int plantedIn) {
        return baseMapper.promoteProposal(id, plantedIn);
    }

    @Override
    public int scheduleRecovery(long id, int recoveredIn) {
        return baseMapper.scheduleRecovery(id, recoveredIn);
    }

    @Override
    public int markPlanted(long novelId, int chapterNo) {
        return baseMapper.markPlanted(novelId, chapterNo);
    }

    @Override
    public int markRecovered(long novelId, int chapterNo) {
        return baseMapper.markRecovered(novelId, chapterNo);
    }
}
