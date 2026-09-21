package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.PromptTemplateMapper;
import com.zzdzz.novelgen.model.dto.PromptTemplateDTO;
import com.zzdzz.novelgen.service.data.PromptTemplateDataService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** prompt_templates 数据服务实现。 */
@Service
public class PromptTemplateDataServiceImpl extends ServiceImpl<PromptTemplateMapper, PromptTemplateDTO> implements PromptTemplateDataService {

    @Override
    public int updateContent(long id, String content) {
        return baseMapper.updateContent(id, content);
    }

    @Override
    public int reset(long id, String content, String catalogHash) {
        return baseMapper.reset(id, content, catalogHash);
    }

    @Override
    public List<PromptTemplateDTO> findAll() {
        return baseMapper.findAll();
    }

    @Override
    public java.util.Optional<PromptTemplateDTO> findById(long id) {
        return java.util.Optional.ofNullable(baseMapper.findById(id));
    }

    @Override
    public int syncInsertIfMissing(String node, String phase, String title, String content, boolean exact, String catalogHash) {
        return baseMapper.syncInsertIfMissing(node, phase, title, content, exact, catalogHash);
    }

    @Override
    public int syncUpdateStale(String node, String phase, String title, String content, boolean exact, String catalogHash) {
        return baseMapper.syncUpdateStale(node, phase, title, content, exact, catalogHash);
    }

    @Override
    public int syncTouch(String node, String phase) {
        return baseMapper.syncTouch(node, phase);
    }

    @Override
    public java.util.Optional<Reset> findNodePhase(long id) {
        var rows = baseMapper.findNodePhase(id);
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(rows.get(0));
    }


    @Override
    public void sync(String node, String phase, String title, String content, boolean exact, String catalogHash) {
        baseMapper.syncInsertIfMissing(node, phase, title, content, exact, catalogHash);
        int stale = baseMapper.syncUpdateStale(node, phase, title, content, exact, catalogHash);
        if (stale > 0) {
            baseMapper.syncTouch(node, phase);
        }
    }
}
