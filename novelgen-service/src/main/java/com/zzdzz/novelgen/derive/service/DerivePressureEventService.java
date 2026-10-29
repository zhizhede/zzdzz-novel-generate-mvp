package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.DerivePressureEventMapper;
import com.zzdzz.novelgen.model.entity.derive.DerivePressureEventDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 压力事件：从目标差、关系差、组织压力和制度代价生成并映射到章/拍。 */
@Service
@RequiredArgsConstructor
public class DerivePressureEventService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DerivePressureEventMapper mapper;

    public record PressureEventInput(String eventKey, String eventType, String title,
                                     String triggerCharacterKey, List<String> targetCharacterKeys,
                                     String organizationKey, String institutionKey,
                                     String publicSurface, String hiddenPressure,
                                     String beforeState, String afterState,
                                     String relationshipChanges, String characterChanges,
                                     Integer chapterNo, Integer seq) {
    }

    public List<String> validate(PressureEventInput event, Collection<String> characterKeys,
                                 Collection<String> organizationKeys, Collection<String> institutionKeys) {
        List<String> errors = new ArrayList<>();
        if (blank(event.publicSurface())) {
            errors.add("PRESSURE_EVENT_SURFACE_MISSING");
        }
        if (blank(event.hiddenPressure())) {
            errors.add("PRESSURE_EVENT_HIDDEN_PRESSURE_MISSING");
        }
        if (event.targetCharacterKeys() == null || event.targetCharacterKeys().isEmpty()) {
            errors.add("PRESSURE_EVENT_NO_TARGET");
        } else if (characterKeys != null) {
            for (String key : event.targetCharacterKeys()) {
                if (!characterKeys.contains(key)) {
                    errors.add("PRESSURE_EVENT_UNKNOWN_TARGET");
                    break;
                }
            }
        }
        if (blank(event.beforeState()) || blank(event.afterState()) || blank(event.relationshipChanges())
                && blank(event.characterChanges())) {
            errors.add("PRESSURE_EVENT_NO_STATE_CHANGE");
        }
        if (blank(event.relationshipChanges()) && blank(event.characterChanges())) {
            errors.add("PRESSURE_EVENT_NO_RELATION_OR_CHARACTER_CHANGE");
        }
        if (event.chapterNo() == null || event.seq() == null || event.chapterNo() < 1 || event.seq() < 1) {
            errors.add("PRESSURE_EVENT_UNMAPPED");
        }
        if (!blank(event.organizationKey()) && organizationKeys != null
                && !organizationKeys.contains(event.organizationKey())) {
            errors.add("PRESSURE_EVENT_UNKNOWN_ORGANIZATION");
        }
        if (!blank(event.institutionKey()) && institutionKeys != null
                && !institutionKeys.contains(event.institutionKey())) {
            errors.add("PRESSURE_EVENT_UNKNOWN_INSTITUTION");
        }
        return errors;
    }

    public void requireValid(PressureEventInput event, Collection<String> characterKeys,
                             Collection<String> organizationKeys, Collection<String> institutionKeys) {
        List<String> errors = validate(event, characterKeys, organizationKeys, institutionKeys);
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "压力事件不合规：" + String.join("、", errors));
        }
    }

    public DerivePressureEventDO save(long runId, PressureEventInput input, Collection<String> characterKeys,
                                      Collection<String> organizationKeys, Collection<String> institutionKeys) {
        requireValid(input, characterKeys, organizationKeys, institutionKeys);
        DerivePressureEventDO row = mapper.selectOne(new LambdaQueryWrapper<DerivePressureEventDO>()
                .eq(DerivePressureEventDO::getRunId, runId)
                .eq(DerivePressureEventDO::getEventKey, input.eventKey()).last("LIMIT 1"));
        boolean insert = row == null;
        if (insert) {
            row = new DerivePressureEventDO();
            row.setRunId(runId);
            row.setEventKey(input.eventKey());
        }
        row.setEventType(input.eventType());
        row.setTitle(input.title());
        row.setTriggerCharacterKey(input.triggerCharacterKey());
        row.setTargetCharacterKeys(toJson(input.targetCharacterKeys()));
        row.setOrganizationKey(input.organizationKey());
        row.setInstitutionKey(input.institutionKey());
        row.setPublicSurface(input.publicSurface());
        row.setHiddenPressure(input.hiddenPressure());
        row.setBeforeState(input.beforeState());
        row.setAfterState(input.afterState());
        row.setRelationshipChanges(input.relationshipChanges());
        row.setCharacterChanges(input.characterChanges());
        row.setChapterNo(input.chapterNo());
        row.setSeq(input.seq());
        row.setStatus("PLANNED");
        if (insert) {
            mapper.insertFull(row);
        } else {
            mapper.updateById(row);
        }
        return row;
    }

    public List<DerivePressureEventDO> load(long runId) {
        return mapper.selectList(new LambdaQueryWrapper<DerivePressureEventDO>()
                .eq(DerivePressureEventDO::getRunId, runId)
                .orderByAsc(DerivePressureEventDO::getChapterNo, DerivePressureEventDO::getSeq));
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
