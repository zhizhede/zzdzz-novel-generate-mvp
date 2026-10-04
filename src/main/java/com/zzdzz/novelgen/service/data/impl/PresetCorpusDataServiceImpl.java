package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.PresetCorpusMapper;
import com.zzdzz.novelgen.model.entity.PresetCorpusDO;
import com.zzdzz.novelgen.service.data.PresetCorpusDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 品类语料数据服务实现。 */
@Service
public class PresetCorpusDataServiceImpl extends ServiceImpl<PresetCorpusMapper, PresetCorpusDO>
        implements PresetCorpusDataService {

    @Override
    public List<PresetCorpusDO> listByGenre(String genre) {
        return list(new QueryWrapper<PresetCorpusDO>()
                .eq("genre", genre)
                .orderByAsc("id"));
    }

    @Override
    public List<GenreSummary> genreSummaries() {
        return list(new QueryWrapper<PresetCorpusDO>()).stream()
                .collect(java.util.stream.Collectors.groupingBy(PresetCorpusDO::getGenre))
                .entrySet().stream()
                .map(e -> new GenreSummary(e.getKey(), e.getValue().size(),
                        e.getValue().stream().mapToLong(c -> c.getWordCount() == null ? 0 : c.getWordCount()).sum()))
                .sorted(java.util.Comparator.comparing(GenreSummary::genre))
                .toList();
    }
}
