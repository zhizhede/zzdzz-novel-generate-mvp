package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.LlmNodeConfigMapper;
import com.zzdzz.novelgen.model.dto.LlmNodeConfigDTO;
import com.zzdzz.novelgen.service.data.LlmNodeConfigDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** llm_node_configs 数据服务实现。 */
@Service
public class LlmNodeConfigDataServiceImpl extends ServiceImpl<LlmNodeConfigMapper, LlmNodeConfigDTO> implements LlmNodeConfigDataService {

    @Override
    public LlmNodeConfigDTO findEnabled(String node) {
        return baseMapper.findEnabled(node);
    }

    @Override
    public List<LlmNodeConfigDTO> listAll() {
        return baseMapper.listAll();
    }

    @Override
    public LlmNodeConfigDTO findById(long id) {
        return baseMapper.findById(id);
    }

    @Override
    public boolean exists(String node) {
        return baseMapper.exists(node);
    }

    @Override
    public int softDelete(long id) {
        return baseMapper.softDelete(id);
    }

    @Override
    public long insert(String node, String model, Double temperature, Integer maxTokens,
                       String extraJson, boolean enabled, String remark) {
        Long id = baseMapper.insert(node, model, temperature, maxTokens, extraJson, enabled, remark);
        return id == null ? -1L : id;
    }

    @Override
    public void update(long id, String model, Double temperature, Integer maxTokens,
                       String extraJson, boolean enabled, String remark) {
        baseMapper.update(id, model, temperature, maxTokens, extraJson, enabled, remark);
    }
}
