package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.CanonDocDO;

import java.util.List;

/** canon_docs 数据服务接口（原 CanonDocDAO）。 */
public interface CanonDocDataService extends IService<CanonDocDO> {

    boolean exists(long novelId, String kind, String name);

    int insert(long novelId, String kind, String name, String content);

    String findFirstByKind(long novelId, String kind);

    List<CanonDocDO> listByNovel(long novelId);

    /** 全库同类同名文档（规划资产页读「各书大纲」用：kind=misc + name=大纲）。 */
    List<CanonDocDO> listAliveByKindName(String kind, String name);

    Long findId(long novelId, String kind, String name);

    String findContentByKindName(long novelId, String kind, String name);

    CanonDocDO findById(long id);

    int updateContent(long id, String content);

    int softDelete(long id);
}
