package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.NovelDO;

import java.util.List;

/** novels 数据服务接口（原 NovelDAO）。 */
public interface NovelDataService extends IService<NovelDO> {

    List<NovelDO> listAlive();

    Long findIdByTitle(String title);

    long insert(long userId, String title, String description, Long stylePackId, String approvalMode);

    String findApprovalMode(long novelId);

    int updateApprovalMode(long novelId, String mode);

    String findPlanMode(long novelId);

    int updatePlanMode(long novelId, String mode);

    int chapterCount(long novelId);
}
