package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.MaterialCardDO;

import java.util.List;

/** material_cards 数据服务接口（原 MaterialCardDAO）。 */
public interface MaterialCardDataService extends IService<MaterialCardDO> {

    List<MaterialCardDO> listByNovel(long novelId, String kind);

    MaterialCardDO findById(long id);

    boolean exists(long novelId, String kind, String name);

    /** 除自己以外是否还有同名活卡（改名撞键前置校验）。 */
    boolean existsOther(long novelId, String kind, String name, long excludeId);

    int softDelete(long id);

    boolean hasCards(long novelId);

    void insert(long novelId, String kind, String name, List<String> aliases, String summary,
                String contentMd, boolean pinned, String status, Integer sourceChapter);

    void update(long id, String name, List<String> aliases, String summary, String contentMd,
                Boolean pinned, String status, Integer sourceChapter);
}
