package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.MaterialCardDTO;

import java.util.List;

/** material_cards 数据服务接口（原 MaterialCardDAO）。 */
public interface MaterialCardDataService extends IService<MaterialCardDTO> {

    List<MaterialCardDTO> listByNovel(long novelId, String kind);

    MaterialCardDTO findById(long id);

    boolean exists(long novelId, String kind, String name);

    int softDelete(long id);

    boolean hasCards(long novelId);

    void insert(long novelId, String kind, String name, List<String> aliases, String summary,
                String contentMd, boolean pinned, String status, Integer sourceChapter);

    void update(long id, String name, List<String> aliases, String summary, String contentMd,
                Boolean pinned, String status, Integer sourceChapter);
}
