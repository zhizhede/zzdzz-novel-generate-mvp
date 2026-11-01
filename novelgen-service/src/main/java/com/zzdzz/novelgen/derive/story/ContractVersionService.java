package com.zzdzz.novelgen.derive.story;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.DeriveChapterScheduleMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveGenreContractMapper;
import com.zzdzz.novelgen.derive.mapper.DeriveStoryContractMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveChapterScheduleDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveGenreContractDO;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryContractDO;
import com.zzdzz.novelgen.model.entity.derive.RunDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 合同版本一致性守卫：故事合同、类型合同与章级日程必须使用同一版本号。
 */
@Service
@RequiredArgsConstructor
public class ContractVersionService {

    private final DeriveStoryContractMapper storyMapper;
    private final DeriveGenreContractMapper genreMapper;
    private final DeriveChapterScheduleMapper scheduleMapper;

    /** 当前三处版本快照；缺行按旧 run 兼容口径回填，显式 0 保留用于检测历史脏数据。 */
    public ContractVersionSnapshot current(RunDO run) {
        if (run == null || run.getId() == null) {
            return new ContractVersionSnapshot(0, 0, -1);
        }
        DeriveStoryContractDO story = storyMapper == null ? null : storyMapper.selectOne(
                new LambdaQueryWrapper<DeriveStoryContractDO>()
                        .eq(DeriveStoryContractDO::getRunId, run.getId())
                        .orderByDesc(DeriveStoryContractDO::getVersion)
                        .last("LIMIT 1"));
        DeriveGenreContractDO genre = genreMapper == null ? null
                : genreMapper.findByRun(run.getId());
        List<DeriveChapterScheduleDO> scheduleRows = scheduleMapper == null ? List.of()
                : scheduleMapper.selectByRun(run.getId());
        int storyVersion = story == null ? 0
                : story.getVersion() == null ? 0 : story.getVersion();
        int genreVersion = genre == null ? 0
                : genre.getContractVersion() == null ? 0 : genre.getContractVersion();
        if (story == null) {
            storyVersion = genreVersion;
        }
        if (genre == null) {
            genreVersion = storyVersion;
        }
        int scheduleVersion = -1;
        if (!scheduleRows.isEmpty()) {
            Integer stored = scheduleRows.get(0).getContractVersion();
            scheduleVersion = stored == null ? 0 : stored;
        }
        return new ContractVersionSnapshot(storyVersion, genreVersion, scheduleVersion);
    }

    /** 版本不一致 fail-fast；旧 run 没有类型合同行时按故事合同兼容。 */
    public void assertConsistent(RunDO run) {
        if (run == null || run.getId() == null) {
            return;
        }
        ContractVersionSnapshot snapshot = current(run);
        DeriveGenreContractDO genreRow = genreMapper == null ? null
                : genreMapper.findByRun(run.getId());
        int effectiveStory = snapshot.storyContractVersion() > 0
                ? snapshot.storyContractVersion() : snapshot.genreContractVersion();
        if (genreRow != null && effectiveStory > 0 && snapshot.genreContractVersion() != effectiveStory) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "CONTRACT_VERSION_MISMATCH：故事合同版本 "
                            + effectiveStory
                            + " 与类型合同版本 " + snapshot.genreContractVersion()
                            + " 不一致，必须重新冻结合同");
        }
        List<DeriveChapterScheduleDO> scheduleRows = scheduleMapper == null ? List.of()
                : scheduleMapper.selectByRun(run.getId());
        if (!scheduleRows.isEmpty() && effectiveStory > 0) {
            Integer stored = scheduleRows.get(0).getContractVersion();
            int scheduleVersion = stored == null ? 0 : stored;
            if (scheduleVersion != effectiveStory) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "SCHEDULE_STALE：日程合同版本 " + scheduleVersion
                                + " 与合同 " + effectiveStory
                                + " 不一致，必须重新投影");
            }
        }
    }
    /** 三处合同版本快照；scheduleVersion=-1 表示尚无日程行。 */
    public record ContractVersionSnapshot(
            int storyContractVersion,
            int genreContractVersion,
            int scheduleVersion) {
    }
}