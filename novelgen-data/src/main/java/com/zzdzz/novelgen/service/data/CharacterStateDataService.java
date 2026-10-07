package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.CharacterStateDO;

import java.util.List;

/** character_states 数据服务接口：人物状态账的读写（投影自 world_states，见 CharacterStateService）。 */
public interface CharacterStateDataService extends IService<CharacterStateDO> {

    /** 一行账的输入（possessions 已是 JSON 数组字符串，null 表示不记物品）。 */
    record Row(String name, String location, String possessions) {
    }

    /** 整章账重写（先删后插，幂等）：digest 重算不产生第二份账。 */
    void replaceChapter(long novelId, int chapterNo, List<Row> rows);

    List<CharacterStateDO> listByChapter(long novelId, int chapterNo);

    List<CharacterStateDO> listByNovel(long novelId);

    /** 某名字在 beforeChapter 之前最近一次的状态（无则 null）。 */
    CharacterStateDO latestBefore(long novelId, String name, int beforeChapter);
}
