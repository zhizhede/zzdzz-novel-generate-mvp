package com.zzdzz.novelgen.service.data.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zzdzz.novelgen.model.entity.SampleParseTaskDO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/**
 * 解析任务重复提交基线：sample_parse_tasks 有活跃唯一索引 uq_sample_parse_task_alive，
 * 「先查没有 → 再插入」之间存在并发窗口（按钮双击）。撞键时要复用在飞的那一行，
 * 而不是把唯一键冲突抛到界面；双跑本身由 runParse 的 QUEUED→RUNNING CAS 兜住。
 */
class SampleParseTaskDataServiceImplTest {

    @Test
    void concurrentDoubleSubmitReusesTheRowThatWon() {
        SampleParseTaskDataServiceImpl impl = spy(new SampleParseTaskDataServiceImpl());
        SampleParseTaskDO twin = new SampleParseTaskDO();
        twin.setId(88L);
        twin.setSampleId(7L);
        twin.setStatus("QUEUED");
        doReturn(null).doReturn(twin).when(impl).findAliveBySample(7L);
        doThrow(new DuplicateKeyException("uq_sample_parse_task_alive")).when(impl).save(any());
        doReturn(true).when(impl).update(any(Wrapper.class));

        assertThat(impl.resetForRun(7L, "FULL")).isEqualTo(88L);
    }

    @Test
    void realDuplicateKeyIsStillThrownWhenNoRowIsFound() {
        SampleParseTaskDataServiceImpl impl = spy(new SampleParseTaskDataServiceImpl());
        doReturn(null).when(impl).findAliveBySample(7L);
        doThrow(new DuplicateKeyException("别的唯一键")).when(impl).save(any());

        try {
            impl.resetForRun(7L, "FAST");
            org.assertj.core.api.Assertions.fail("应把非并发场景的唯一键冲突原样抛出");
        } catch (DuplicateKeyException e) {
            assertThat(e.getMessage()).isEqualTo("别的唯一键");
        }
    }

    /** 非唯一键的约束冲突（外键/非空）不该被这条兜底吞掉：重查不到行就原样抛。 */
    @Test
    void otherConstraintViolationIsNotSwallowed() {
        SampleParseTaskDataServiceImpl impl = spy(new SampleParseTaskDataServiceImpl());
        doReturn(null).when(impl).findAliveBySample(7L);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("null value in column \"mode\""))
                .when(impl).save(any());

        try {
            impl.resetForRun(7L, "FAST");
            org.assertj.core.api.Assertions.fail("非并发的约束冲突必须往上抛");
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            assertThat(e.getMessage()).contains("mode");
        }
    }
}
