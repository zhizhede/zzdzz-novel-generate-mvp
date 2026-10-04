package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.LlmProviderMapper;
import com.zzdzz.novelgen.model.entity.LlmProviderDO;
import com.zzdzz.novelgen.service.data.LlmProviderDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** llm_providers 数据服务实现。 */
@Service
public class LlmProviderDataServiceImpl extends ServiceImpl<LlmProviderMapper, LlmProviderDO> implements LlmProviderDataService {

    @Override
    public List<LlmProviderDO> listEnabled(String role) {
        return list(new QueryWrapper<LlmProviderDO>()
                .eq("enabled", true)
                .eq("role", role)
                .orderByAsc("id"));
    }

    @Override
    public List<LlmProviderDO> listAll() {
        return list(new QueryWrapper<LlmProviderDO>()
                .orderByAsc("id"));
    }

    @Override
    public LlmProviderDO findById(long id) {
        return getOne(new QueryWrapper<LlmProviderDO>()
                .eq("id", id));
    }

    @Override
    public LlmProviderDO findByName(String name) {
        return getOne(new QueryWrapper<LlmProviderDO>()
                .eq("name", name));
    }

    @Override
    public int delete(long id) {
        return baseMapper.deleteById(id);
    }

    @Override
    public void disableAllOthersInRole(long keepId, String role) {
        update(new UpdateWrapper<LlmProviderDO>()
                .ne("id", keepId)
                .eq("role", role)
                .eq("enabled", true)
                .set("enabled", false));
    }
}
