package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.LlmProviderMapper;
import com.zzdzz.novelgen.model.dto.LlmProviderDTO;
import com.zzdzz.novelgen.service.data.LlmProviderDataService;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * llm_providers 数据服务实现（软删三件套口径与全库一致）。
 * Wrapper 用字符串列名——lambda cache 解析不了 isDeleted 布尔字段（与 OutlineDraftService 同款做法）。
 */
@Service
public class LlmProviderDataServiceImpl extends ServiceImpl<LlmProviderMapper, LlmProviderDTO> implements LlmProviderDataService {

    @Override
    public List<LlmProviderDTO> listEnabled() {
        return list(new QueryWrapper<LlmProviderDTO>()
                .eq("enabled", true)
                .eq("is_deleted", false)
                .orderByAsc("id"));
    }

    @Override
    public List<LlmProviderDTO> listAll() {
        return list(new QueryWrapper<LlmProviderDTO>()
                .eq("is_deleted", false)
                .orderByAsc("id"));
    }

    @Override
    public LlmProviderDTO findById(long id) {
        return getOne(new QueryWrapper<LlmProviderDTO>()
                .eq("id", id)
                .eq("is_deleted", false));
    }

    @Override
    public LlmProviderDTO findByName(String name) {
        return getOne(new QueryWrapper<LlmProviderDTO>()
                .eq("name", name)
                .eq("is_deleted", false));
    }

    @Override
    public int softDelete(long id) {
        return baseMapper.update(null, new UpdateWrapper<LlmProviderDTO>()
                .eq("id", id)
                .set("is_deleted", true)
                .set("enabled", false)
                .set("delete_time", OffsetDateTime.now())) > 0 ? 1 : 0;
    }

    @Override
    public void disableAllOthers(long keepId) {
        update(new UpdateWrapper<LlmProviderDTO>()
                .ne("id", keepId)
                .eq("is_deleted", false)
                .eq("enabled", true)
                .set("enabled", false));
    }
}
