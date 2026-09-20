package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.entity.RetroProposalDO;

import java.util.List;

/** 复盘建议/提案数据服务（流 D）：生成端去重提案，人工采纳/忽略。 */
public interface RetroProposalDataService extends IService<RetroProposalDO> {

    /** 提案一条（同卷同内容去重；已决策的不重复提案）。 */
    void propose(long novelId, int volNo, String kind, String content);

    List<RetroProposalDO> listByVolume(long novelId, int volNo);

    /** 采纳/忽略；返回是否生效（未决→已决）。 */
    boolean decide(long id, boolean adopt, String note);
}
