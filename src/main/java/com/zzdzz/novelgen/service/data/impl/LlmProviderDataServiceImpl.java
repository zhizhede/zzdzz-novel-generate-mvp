package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.LlmProviderMapper;
import com.zzdzz.novelgen.model.entity.LlmProviderDO;
import com.zzdzz.novelgen.service.data.LlmProviderDataService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * llm_providers 数据服务实现（软删三件套口径与全库一致）。
 * Wrapper 用字符串列名并显式 `.eq("is_deleted", false)`——实体不带软删字段，过滤必须自己带（与 OutlineDraftService 同款做法）。
 */
@Service
public class LlmProviderDataServiceImpl extends ServiceImpl<LlmProviderMapper, LlmProviderDO> implements LlmProviderDataService {

    @Override
    public List<LlmProviderDO> listEnabled(String role) {
        return list(new QueryWrapper<LlmProviderDO>()
                .eq("enabled", true)
                .eq("role", role)
                .eq("is_deleted", false)
                .orderByAsc("id"));
    }

    @Override
    public List<LlmProviderDO> listAll() {
        return list(new QueryWrapper<LlmProviderDO>()
                .eq("is_deleted", false)
                .orderByAsc("id"));
    }

    @Override
    public LlmProviderDO findById(long id) {
        return getOne(new QueryWrapper<LlmProviderDO>()
                .eq("id", id)
                .eq("is_deleted", false));
    }

    @Override
    public LlmProviderDO findByName(String name) {
        return getOne(new QueryWrapper<LlmProviderDO>()
                .eq("name", name)
                .eq("is_deleted", false));
    }

    @Override
    public int softDelete(long id) {
        return baseMapper.update(null, new UpdateWrapper<LlmProviderDO>()
                .eq("id", id)
                .set("is_deleted", true)
                .set("enabled", false)
                .set("delete_time", OffsetDateTime.now())) > 0 ? 1 : 0;
    }

    @Override
    public void disableAllOthersInRole(long keepId, String role) {
        update(new UpdateWrapper<LlmProviderDO>()
                .ne("id", keepId)
                .eq("role", role)
                .eq("is_deleted", false)
                .eq("enabled", true)
                .set("enabled", false));
    }
}
