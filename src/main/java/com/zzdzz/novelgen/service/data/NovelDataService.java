package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.NovelDTO;

import java.util.List;

/** novels 数据服务接口（原 NovelDAO）。 */
public interface NovelDataService extends IService<NovelDTO> {

    List<NovelDTO> listAlive();

    Long findIdByTitle(String title);

    long insert(long userId, String title, String description, Long stylePackId, String approvalMode);

    String findApprovalMode(long novelId);

    int updateApprovalMode(long novelId, String mode);

    String findPlanMode(long novelId);

    int updatePlanMode(long novelId, String mode);

    int chapterCount(long novelId);

    /** 衍生配置 JSON 文本（novels.derive_config::text）；无配置返回 null。 */
    String findDeriveConfig(long novelId);

    /** 衍生配置落库（JSON 文本，XML 内 ::jsonb 转型）。 */
    int updateDeriveConfig(long novelId, String deriveConfigJson);

    /** 无人续跑链状态行（auto_state 为 null=未启用）。 */
    record AutoStateRow(String autoState, String autoMessage, int autoVolumes) {
    }

    AutoStateRow findAutoState(long novelId);

    /** 链状态落库（state/message；state=null 表示清空展示）。 */
    int updateAutoState(long novelId, String state, String message);

    /** 自动规划卷数 +1（保险丝计数）。 */
    int bumpAutoVolumes(long novelId);
}
