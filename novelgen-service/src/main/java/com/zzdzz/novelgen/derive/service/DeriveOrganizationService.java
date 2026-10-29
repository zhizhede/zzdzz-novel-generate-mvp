package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.DeriveOrganizationMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveOrganizationDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 组织业务模型：规则、压力和弱点必须能实际改变角色选择。 */
@Service
@RequiredArgsConstructor
public class DeriveOrganizationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DeriveOrganizationMapper mapper;

    public record OrganizationInput(String orgKey, String orgType, String name, String publicGoal,
                                    String privateGoal, String resource, String rule, String weakness,
                                    String pressure, List<String> members) {
    }

    public List<String> validate(OrganizationInput organization, Collection<String> characterKeys) {
        List<String> errors = new ArrayList<>();
        if (blank(organization.publicGoal())) {
            errors.add("ORGANIZATION_GOAL_MISSING");
        }
        if (blank(organization.rule())) {
            errors.add("ORGANIZATION_RULE_MISSING");
        }
        if (blank(organization.pressure())) {
            errors.add("ORGANIZATION_PRESSURE_MISSING");
        }
        if (blank(organization.weakness())) {
            errors.add("ORGANIZATION_WEAKNESS_MISSING");
        }
        if (organization.members() != null && characterKeys != null) {
            for (String member : organization.members()) {
                if (!characterKeys.contains(member)) {
                    errors.add("ORGANIZATION_UNKNOWN_MEMBER");
                    break;
                }
            }
        }
        if (blank(organization.publicGoal()) && blank(organization.rule())
                && blank(organization.pressure()) && blank(organization.weakness())) {
            errors.add("ORGANIZATION_LABEL_ONLY");
        }
        return errors;
    }

    public void requireValid(OrganizationInput organization, Collection<String> characterKeys) {
        List<String> errors = validate(organization, characterKeys);
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "组织不合规：" + String.join("、", errors));
        }
    }

    public DeriveOrganizationDO save(long runId, OrganizationInput input, Collection<String> characterKeys) {
        requireValid(input, characterKeys);
        DeriveOrganizationDO row = mapper.selectOne(new LambdaQueryWrapper<DeriveOrganizationDO>()
                .eq(DeriveOrganizationDO::getRunId, runId)
                .eq(DeriveOrganizationDO::getOrgKey, input.orgKey()).last("LIMIT 1"));
        boolean insert = row == null;
        if (insert) {
            row = new DeriveOrganizationDO();
            row.setRunId(runId);
            row.setOrgKey(input.orgKey());
        }
        row.setOrgType(input.orgType());
        row.setName(input.name());
        row.setPublicGoal(input.publicGoal());
        row.setPrivateGoal(input.privateGoal());
        row.setResource(input.resource());
        row.setRule(input.rule());
        row.setWeakness(input.weakness());
        row.setPressure(input.pressure());
        row.setMembers(toJson(input.members()));
        row.setContract("{}");
        if (insert) {
            mapper.insertFull(row);
        } else {
            mapper.updateById(row);
        }
        return row;
    }

    public List<DeriveOrganizationDO> load(long runId) {
        return mapper.selectList(new LambdaQueryWrapper<DeriveOrganizationDO>()
                .eq(DeriveOrganizationDO::getRunId, runId).orderByAsc(DeriveOrganizationDO::getOrgKey));
    }

    private static String toJson(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
