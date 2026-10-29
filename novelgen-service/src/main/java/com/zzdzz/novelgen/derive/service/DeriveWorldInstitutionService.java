package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.DeriveWorldInstitutionMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveWorldInstitutionDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 世界制度业务模型：制度必须有执行方式、代价并影响角色。 */
@Service
@RequiredArgsConstructor
public class DeriveWorldInstitutionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DeriveWorldInstitutionMapper mapper;

    public record InstitutionInput(String institutionKey, String institutionType, String name,
                                   String lawOrDoctrine, String enforcement, String benefit,
                                   String cost, List<String> affectedCharacters) {
    }

    public List<String> validate(InstitutionInput institution, Collection<String> characterKeys) {
        List<String> errors = new ArrayList<>();
        if (blank(institution.lawOrDoctrine())) {
            errors.add("INSTITUTION_LAW_MISSING");
        }
        if (blank(institution.enforcement())) {
            errors.add("INSTITUTION_ENFORCEMENT_MISSING");
        }
        if (blank(institution.cost())) {
            errors.add("INSTITUTION_COST_MISSING");
        }
        if (institution.affectedCharacters() == null || institution.affectedCharacters().isEmpty()) {
            errors.add("INSTITUTION_NO_AFFECTED_CHARACTER");
        } else if (characterKeys != null) {
            for (String key : institution.affectedCharacters()) {
                if (!characterKeys.contains(key)) {
                    errors.add("INSTITUTION_UNKNOWN_CHARACTER");
                    break;
                }
            }
        }
        if (blank(institution.lawOrDoctrine()) && blank(institution.enforcement())
                && blank(institution.cost())) {
            errors.add("INSTITUTION_LABEL_ONLY");
        }
        return errors;
    }

    public void requireValid(InstitutionInput institution, Collection<String> characterKeys) {
        List<String> errors = validate(institution, characterKeys);
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "世界制度不合规：" + String.join("、", errors));
        }
    }

    public DeriveWorldInstitutionDO save(long runId, InstitutionInput input, Collection<String> characterKeys) {
        requireValid(input, characterKeys);
        DeriveWorldInstitutionDO row = mapper.selectOne(new LambdaQueryWrapper<DeriveWorldInstitutionDO>()
                .eq(DeriveWorldInstitutionDO::getRunId, runId)
                .eq(DeriveWorldInstitutionDO::getInstitutionKey, input.institutionKey()).last("LIMIT 1"));
        boolean insert = row == null;
        if (insert) {
            row = new DeriveWorldInstitutionDO();
            row.setRunId(runId);
            row.setInstitutionKey(input.institutionKey());
        }
        row.setInstitutionType(input.institutionType());
        row.setName(input.name());
        row.setLawOrDoctrine(input.lawOrDoctrine());
        row.setEnforcement(input.enforcement());
        row.setBenefit(input.benefit());
        row.setCost(input.cost());
        row.setAffectedCharacters(toJson(input.affectedCharacters()));
        row.setContract("{}");
        if (insert) {
            mapper.insertFull(row);
        } else {
            mapper.updateById(row);
        }
        return row;
    }

    public List<DeriveWorldInstitutionDO> load(long runId) {
        return mapper.selectList(new LambdaQueryWrapper<DeriveWorldInstitutionDO>()
                .eq(DeriveWorldInstitutionDO::getRunId, runId).orderByAsc(DeriveWorldInstitutionDO::getInstitutionKey));
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
