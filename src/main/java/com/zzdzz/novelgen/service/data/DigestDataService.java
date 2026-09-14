package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.DigestDO;

import java.util.List;

/** digests 数据服务接口（原 DigestDAO）。 */
public interface DigestDataService extends IService<DigestDO> {

    /** 素材库行：带章号（内部模型，非表行）。 */
    record DigestItem(long id, int chapterNo, String contentMd, String facts, String updateTime) {
    }

    /** 素材库：全作品事实账列表（时间正序）。 */
    List<DigestItem> listByNovel(long novelId);

    int updateContent(long id, String contentMd, String factsJson);

    List<String> findRecent(long novelId, int beforeChapter, int n);

    int insert(long chapterId, String contentMd, String factsJson);

    boolean existsByChapter(long chapterId);
}
