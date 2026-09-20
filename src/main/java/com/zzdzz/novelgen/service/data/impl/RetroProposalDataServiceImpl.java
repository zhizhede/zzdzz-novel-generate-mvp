package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zzdzz.novelgen.dao.RetroProposalMapper;
import com.zzdzz.novelgen.model.entity.RetroProposalDO;
import com.zzdzz.novelgen.service.data.RetroProposalDataService;
import org.springframework.stereotype.Service;

import java.util.List;

/** 复盘建议/提案数据服务实现。查询用列名 Wrapper（boolean isDeleted 不入 MP lambda 缓存）。 */
@Service
public class RetroProposalDataServiceImpl extends ServiceImpl<RetroProposalMapper, RetroProposalDO>
        implements RetroProposalDataService {

    @Override
    public void propose(long novelId, int volNo, String kind, String content) {
        String trimmed = content == null ? "" : content.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        Long dup = getObj(new QueryWrapper<RetroProposalDO>()
                .select("id")
                .eq("novel_id", novelId)
                .eq("vol_no", volNo)
                .eq("content", trimmed)
                .eq("is_deleted", false)
                .last("LIMIT 1"), o -> ((Number) o).longValue());
        if (dup != null) {
            return;
        }
        RetroProposalDO row = new RetroProposalDO();
        row.setNovelId(novelId);
        row.setVolNo(volNo);
        row.setKind(kind);
        row.setContent(trimmed);
        row.setStatus("PROPOSED");
        save(row);
    }

    @Override
    public List<RetroProposalDO> listByVolume(long novelId, int volNo) {
        return list(new QueryWrapper<RetroProposalDO>()
                .eq("novel_id", novelId)
                .eq("vol_no", volNo)
                .eq("is_deleted", false)
                .orderByAsc("status")
                .orderByDesc("id"));
    }

    @Override
    public boolean decide(long id, boolean adopt, String note) {
        return update(new UpdateWrapper<RetroProposalDO>()
                .eq("id", id)
                .eq("status", "PROPOSED")
                .set("status", adopt ? "ADOPTED" : "REJECTED")
                .set("decision_note", note));
    }
}
