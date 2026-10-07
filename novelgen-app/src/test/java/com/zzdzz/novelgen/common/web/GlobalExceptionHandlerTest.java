package com.zzdzz.novelgen.common.web;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 全局异常出口基线：数据库唯一键/约束冲突不得把原始 SQL、表结构与文件路径原样抛给前端横幅
 * （2026-09-30 实弹：删书后同名重导撞 uq_style_packs_name_alive，界面滚出整段 INSERT 与 mapper 路径）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static DuplicateKeyException dup() {
        return new DuplicateKeyException("""
                ### Error querying database.  Cause: org.postgresql.util.PSQLException: ERROR: duplicate key value \
                violates unique constraint "uq_style_packs_name_alive"
                详细: Key (name)=(导入书C·风格) already exists.
                ### The error may exist in file [.../target/classes/mapper/StylePackMapper.xml]
                ### SQL: INSERT INTO style_packs (name, description, rules_md, fingerprint, gate_config) \
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb) RETURNING id""");
    }

    @Test
    void constraintViolationBecomesReadableMessageWithoutSql() {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        Result<Void> result = handler.handleIntegrity(dup(), resp);

        assertThat(result.code()).isEqualTo(ErrorCode.STATE_CONFLICT.code());
        assertThat(result.message()).contains("同名记录已存在").doesNotContain("INSERT").doesNotContain("mapper");
        assertThat(result.detail()).isNull();
        verify(resp).setStatus(ErrorCode.STATE_CONFLICT.httpStatus().value());
    }

    @Test
    void otherExceptionsKeepTheGenericSystemErrorShape() {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        Result<Void> result = handler.handleOther(new IllegalStateException("炸了"), resp);

        assertThat(result.code()).isEqualTo(ErrorCode.SYSTEM_ERROR.code());
        assertThat(result.message()).isEqualTo("系统错误：炸了");
        verify(resp).setStatus(ErrorCode.SYSTEM_ERROR.httpStatus().value());
    }

    /** 连不上库/驱动栈同样不该外泄：库里带 JDBC URL、主机端口、栈帧。 */
    @Test
    void dataAccessFailureIsReportedAsMiddlewareErrorWithoutJdbcUrl() {
        HttpServletResponse resp = mock(HttpServletResponse.class);
        Result<Void> result = handler.handleDataAccess(new org.springframework.dao.DataAccessResourceFailureException(
                "### Error querying database. Cause: org.postgresql.util.PSQLException: Connection to "
                        + "pg-vector:5432 refused. Check that the hostname and port are correct"), resp);

        assertThat(result.code()).isEqualTo(ErrorCode.DB_ERROR.code());
        assertThat(result.message()).contains("数据库暂时不可用")
                .doesNotContain("org.postgresql").doesNotContain("5432");
        verify(resp).setStatus(ErrorCode.DB_ERROR.httpStatus().value());
    }
}
