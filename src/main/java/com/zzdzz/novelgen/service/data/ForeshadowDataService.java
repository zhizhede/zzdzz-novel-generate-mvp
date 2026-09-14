package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.ForeshadowDO;

import java.util.List;

/** foreshadows 数据服务接口（原 ForeshadowDAO）。 */
public interface ForeshadowDataService extends IService<ForeshadowDO> {

    List<ForeshadowDO> listByNovel(long novelId);

    boolean exists(long novelId, String code);

    int insert(long novelId, String code, String content, int plantedIn, int recoveredIn);

    int update(long id, String content, Integer plantedIn, Integer recoveredIn, String status);

    ForeshadowDO findByCode(long novelId, String code);

    ForeshadowDO findById(long id);

    int insertProposal(long novelId, String code, String content, int proposedIn);

    boolean contentExists(long novelId, String content);

    String nextCode(long novelId);

    int insertPlanned(long novelId, String code, String content, int plantedIn);

    List<String> findDirectives(long novelId, int chapterNo);

    int promoteProposal(long id, int plantedIn);

    int scheduleRecovery(long id, int recoveredIn);

    int markPlanted(long novelId, int chapterNo);

    int markRecovered(long novelId, int chapterNo);
}
