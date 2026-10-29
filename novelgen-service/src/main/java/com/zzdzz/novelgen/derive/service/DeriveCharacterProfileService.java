package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.DeriveCharacterProfileMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveCharacterProfileDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 角色档案生成与结构化校验；职业/派系标签不能代替决策规则。 */
@Service
@RequiredArgsConstructor
public class DeriveCharacterProfileService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DeriveCharacterProfileMapper mapper;

    /** 角色档案输入合同。 */
    public record ProfileInput(String characterKey, String displayName, String publicGoal,
                               String privateNeed, String contradiction, String wound,
                               String capability, String limitation, String secret,
                               String decisionRule, String speechPattern, String behaviorPattern,
                               String arcState) {
    }

    /** 校验档案字段与闭合引用；错误码与合同规格保持稳定。 */
    public List<String> validate(ProfileInput profile, Collection<String> organizations,
                                 Collection<String> institutions) {
        List<String> errors = new ArrayList<>();
        if (blank(profile.characterKey()) || blank(profile.displayName())) {
            errors.add("CHARACTER_KEY_MISSING");
        }
        if (blank(profile.publicGoal())) {
            errors.add("CHARACTER_GOAL_MISSING");
        }
        if (blank(profile.privateNeed())) {
            errors.add("CHARACTER_NEED_MISSING");
        }
        if (blank(profile.contradiction())) {
            errors.add("CHARACTER_CONTRADICTION_MISSING");
        }
        if (blank(profile.decisionRule())) {
            errors.add("CHARACTER_DECISION_RULE_MISSING");
        }
        if (isLabelOnly(profile)) {
            errors.add("CHARACTER_LABEL_ONLY");
        }
        String org = profile.capability() == null ? "" : profile.capability();
        if (!org.isBlank() && organizations != null && !organizations.isEmpty()
                && !containsAny(org, organizations)) {
            errors.add("CHARACTER_UNKNOWN_ORGANIZATION");
        }
        String institution = profile.limitation() == null ? "" : profile.limitation();
        if (!institution.isBlank() && institutions != null && !institutions.isEmpty()
                && !containsAny(institution, institutions)) {
            errors.add("CHARACTER_UNKNOWN_INSTITUTION");
        }
        return errors;
    }

    public void requireValid(ProfileInput profile, Collection<String> organizations,
                             Collection<String> institutions) {
        List<String> errors = validate(profile, organizations, institutions);
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "角色档案不合规：" + String.join("、", errors));
        }
    }

    /** 幂等保存一个档案；重复 key 更新业务字段。 */
    public DeriveCharacterProfileDO save(long runId, ProfileInput input,
                                         Collection<String> organizations,
                                         Collection<String> institutions) {
        requireValid(input, organizations, institutions);
        DeriveCharacterProfileDO row = mapper.selectOne(
                new LambdaQueryWrapper<DeriveCharacterProfileDO>()
                        .eq(DeriveCharacterProfileDO::getRunId, runId)
                        .eq(DeriveCharacterProfileDO::getCharacterKey, input.characterKey())
                        .last("LIMIT 1"));
        boolean insert = row == null;
        if (insert) {
            row = new DeriveCharacterProfileDO();
            row.setRunId(runId);
            row.setCharacterKey(input.characterKey());
        }
        row.setDisplayName(input.displayName());
        row.setPublicGoal(input.publicGoal());
        row.setPrivateNeed(input.privateNeed());
        row.setContradiction(input.contradiction());
        row.setWound(input.wound());
        row.setCapability(input.capability());
        row.setLimitation(input.limitation());
        row.setSecret(input.secret());
        row.setDecisionRule(input.decisionRule());
        row.setSpeechPattern(input.speechPattern());
        row.setBehaviorPattern(input.behaviorPattern());
        row.setCurrentStatus("ACTIVE");
        row.setArcState(blank(input.arcState()) ? "START" : input.arcState());
        row.setProfile("{}");
        if (insert) {
            mapper.insertFull(row);
        } else {
            mapper.updateById(row);
        }
        return row;
    }

    public List<DeriveCharacterProfileDO> load(long runId) {
        return mapper.selectList(new LambdaQueryWrapper<DeriveCharacterProfileDO>()
                .eq(DeriveCharacterProfileDO::getRunId, runId)
                .orderByAsc(DeriveCharacterProfileDO::getCharacterKey));
    }

    public List<ProfileInput> toInputs(Collection<DeriveCharacterProfileDO> rows) {
        List<ProfileInput> out = new ArrayList<>();
        for (DeriveCharacterProfileDO row : rows) {
            out.add(new ProfileInput(row.getCharacterKey(), row.getDisplayName(), row.getPublicGoal(),
                    row.getPrivateNeed(), row.getContradiction(), row.getWound(), row.getCapability(),
                    row.getLimitation(), row.getSecret(), row.getDecisionRule(), row.getSpeechPattern(),
                    row.getBehaviorPattern(), row.getArcState()));
        }
        return out;
    }

    static boolean isLabelOnly(ProfileInput profile) {
        Set<String> labels = new LinkedHashSet<>(List.of("偶像", "黑帮", "宗教信徒", "警察", "侦探", "主角", "反派",
                "idol", "mafia", "detective", "hero", "villain"));
        for (String field : List.of(profile.publicGoal(), profile.privateNeed(), profile.contradiction(),
                profile.decisionRule(), profile.speechPattern(), profile.behaviorPattern())) {
            if (!blank(field) && field.trim().length() > 16) {
                return false;
            }
        }
        String combined = join(profile.publicGoal(), profile.privateNeed(), profile.contradiction(),
                profile.decisionRule(), profile.speechPattern(), profile.behaviorPattern()).trim();
        if (combined.isEmpty()) {
            return true;
        }
        String normalized = combined.replaceAll("[\\s,，、。/·:：;；()（）]+", "");
        return labels.contains(normalized) || normalized.matches("[\\p{L}]{2,4}")
                || normalized.length() <= 24 && !normalized.matches(".*[0-9，。！？；：、,.;:!?].*");
    }

    private static boolean containsAny(String value, Collection<String> known) {
        for (String key : known) {
            if (!blank(key) && value.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static String join(String... values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (!blank(value)) {
                sb.append(value).append(' ');
            }
        }
        return sb.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public static void requireKeys(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "角色档案不能为空");
        }
    }
}