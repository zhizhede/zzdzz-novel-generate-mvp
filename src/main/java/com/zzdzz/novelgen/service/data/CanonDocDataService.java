package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.CanonDocDTO;

import java.util.List;

/** canon_docs 数据服务接口（原 CanonDocDAO）。 */
public interface CanonDocDataService extends IService<CanonDocDTO> {

    boolean exists(long novelId, String kind, String name);

    int insert(long novelId, String kind, String name, String content);

    String findFirstByKind(long novelId, String kind);

    List<CanonDocDTO> listByNovel(long novelId);

    Long findId(long novelId, String kind, String name);

    String findContentByKindName(long novelId, String kind, String name);

    CanonDocDTO findById(long id);

    int updateContent(long id, String content);

    int softDelete(long id);
}
