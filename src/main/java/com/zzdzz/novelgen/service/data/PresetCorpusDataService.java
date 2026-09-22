package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.PresetCorpusDTO;

import java.util.List;

/** 品类语料数据服务（阶段三·特征提取管线原料）。 */
public interface PresetCorpusDataService extends IService<PresetCorpusDTO> {

    List<PresetCorpusDTO> listByGenre(String genre);

    /** 品类汇总：genre / 章数 / 总字数（品类随时新增，无固定枚举）。 */
    List<GenreSummary> genreSummaries();

    record GenreSummary(String genre, long chapters, long words) {
    }
}
