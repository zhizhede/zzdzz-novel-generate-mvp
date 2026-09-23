package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.StylePackMapper;
import com.zzdzz.novelgen.model.dto.StylePackDTO;
import com.zzdzz.novelgen.service.data.StylePackDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** style_packs 数据服务实现。 */
@Service
public class StylePackDataServiceImpl extends ServiceImpl<StylePackMapper, StylePackDTO> implements StylePackDataService {

    @Override
    public Long findIdByName(String name) {
        return baseMapper.findIdByName(name);
    }

    @Override
    public long insert(String name, String description, String rulesMd, String fingerprint) {
        return baseMapper.insert(name, description, rulesMd, fingerprint);
    }

    @Override
    public int updateFingerprint(long id, String fingerprint) {
        return baseMapper.updateFingerprint(id, fingerprint);
    }

    @Override
    public int updateRulesMdByNovel(long novelId, String rulesMd) {
        return baseMapper.updateRulesMdByNovel(novelId, rulesMd);
    }

    @Override
    public String findGateConfigByNovel(long novelId) {
        return baseMapper.findGateConfigByNovel(novelId);
    }

    @Override
    public int updateGateConfigByNovel(long novelId, String gateConfigJson) {
        return baseMapper.updateGateConfigByNovel(novelId, gateConfigJson);
    }

    @Override
    public String findRulesMdByNovel(long novelId) {
        return baseMapper.findRulesMdByNovel(novelId);
    }

    @Override
    public String findFingerprintByNovel(long novelId) {
        return baseMapper.findFingerprintByNovel(novelId);
    }

    @Override
    public String findGateConfigById(long id) {
        return baseMapper.findGateConfigById(id);
    }

    @Override
    public List<StylePackDTO> listPresets() {
        return list(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<StylePackDTO>()
                .eq("is_preset", true)
                .eq("is_deleted", false)
                .orderByDesc("id"));
    }

    @Override
    public long insertPreset(String name, String description, String rulesMd, String fingerprint, String gateConfig) {
        return baseMapper.insertPreset(name, description, rulesMd, fingerprint, gateConfig);
    }

    @Override
    public long insertPack(String name, String description, String rulesMd, String fingerprint, String gateConfig) {
        return baseMapper.insertPack(name, description, rulesMd, fingerprint, gateConfig);
    }
}
