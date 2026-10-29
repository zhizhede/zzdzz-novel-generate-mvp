package com.zzdzz.novelgen.derive.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveStoryContractDO;
import org.apache.ibatis.annotations.Param;

public interface DeriveStoryContractMapper extends BaseMapper<DeriveStoryContractDO> {
    long insertFull(@Param("p") DeriveStoryContractDO contract);

    /**
     * 只更新约束内核（业务模型能力包合并走这里）：合并能力包不是改写真相，
     * 合同 version 必须保持稳定，禁止借机插新版本。
     */
    int updateKernels(@Param("id") long id, @Param("constraintKernel") String constraintKernel,
                      @Param("sourceHash") String sourceHash);
}
