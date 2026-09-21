package com.zzdzz.novelgen.service.data;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zzdzz.novelgen.model.dto.RetroProposalDTO;
import com.zzdzz.novelgen.model.vo.RetroProposalVO;

import java.util.List;

/** 复盘建议/提案数据服务（流 D）：生成端去重提案，人工采纳/忽略。 */
public interface RetroProposalDataService extends IService<RetroProposalDTO> {

    /** 提案一条（同卷同内容去重；已决策的不重复提案）。 */
    void propose(long novelId, int volNo, String kind, String content);

    List<RetroProposalDTO> listByVolume(long novelId, int volNo);

    /** 提案列表（API 用）：DO 不出 service 层。 */
    List<RetroProposalVO> listByVolumeVO(long novelId, int volNo);

    /** 采纳/忽略；返回是否生效（未决→已决）。 */
    boolean decide(long id, boolean adopt, String note);
}
