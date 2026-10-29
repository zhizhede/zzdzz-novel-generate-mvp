package com.zzdzz.novelgen.derive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zzdzz.novelgen.common.web.BizException;
import com.zzdzz.novelgen.common.web.ErrorCode;
import com.zzdzz.novelgen.derive.mapper.DeriveCharacterRelationshipMapper;
import com.zzdzz.novelgen.model.entity.derive.DeriveCharacterRelationshipDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 结构化关系事实：关系必须有历史或转折，并由压力事件驱动变化。 */
@Service
@RequiredArgsConstructor
public class DeriveRelationshipService {

    private final DeriveCharacterRelationshipMapper mapper;

    /** 关系事实输入合同。 */
    public record RelationshipInput(String fromCharacterKey, String toCharacterKey,
                                    String relationshipType, String publicRelation,
                                    String hiddenRelation, String debt, int trust,
                                    int intimacy, int conflict, String history,
                                    String currentTurn, String payoff, String initialState,
                                    String targetState) {
    }

    public List<String> validate(RelationshipInput relation, Collection<String> characterKeys,
                                 boolean romanceEnabled, boolean hasPressureEvent) {
        List<String> errors = new ArrayList<>();
        if (characterKeys == null || !characterKeys.contains(relation.fromCharacterKey())
                || !characterKeys.contains(relation.toCharacterKey())) {
            errors.add("RELATIONSHIP_MISSING_CHARACTER");
        }
        if (blank(relation.publicRelation()) && blank(relation.hiddenRelation())) {
            errors.add("RELATIONSHIP_EMPTY");
        }
        if (outOfRange(relation.trust()) || outOfRange(relation.intimacy())
                || outOfRange(relation.conflict())) {
            errors.add("RELATIONSHIP_VALUE_OUT_OF_RANGE");
        }
        if (blank(relation.history())) {
            errors.add("RELATIONSHIP_NO_HISTORY");
        }
        if (blank(relation.currentTurn())) {
            errors.add("RELATIONSHIP_NO_TURN");
        }
        if (romanceEnabled && (blank(relation.initialState()) || blank(relation.currentTurn())
                || (blank(relation.payoff()) && blank(relation.targetState())))) {
            errors.add("RELATIONSHIP_ARC_INCOMPLETE");
        }
        if (!hasPressureEvent) {
            errors.add("RELATIONSHIP_NO_PRESSURE_EVENT");
        }
        return errors;
    }

    public void requireValid(RelationshipInput relation, Collection<String> characterKeys,
                             boolean romanceEnabled, boolean hasPressureEvent) {
        List<String> errors = validate(relation, characterKeys, romanceEnabled, hasPressureEvent);
        if (!errors.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "关系事实不合规：" + String.join("、", errors));
        }
    }

    public DeriveCharacterRelationshipDO save(long runId, RelationshipInput input,
                                              Collection<String> characterKeys,
                                              boolean romanceEnabled, boolean hasPressureEvent) {
        requireValid(input, characterKeys, romanceEnabled, hasPressureEvent);
        DeriveCharacterRelationshipDO row = mapper.selectOne(
                new LambdaQueryWrapper<DeriveCharacterRelationshipDO>()
                        .eq(DeriveCharacterRelationshipDO::getRunId, runId)
                        .eq(DeriveCharacterRelationshipDO::getFromCharacterKey, input.fromCharacterKey())
                        .eq(DeriveCharacterRelationshipDO::getToCharacterKey, input.toCharacterKey())
                        .last("LIMIT 1"));
        boolean insert = row == null;
        if (insert) {
            row = new DeriveCharacterRelationshipDO();
            row.setRunId(runId);
            row.setFromCharacterKey(input.fromCharacterKey());
            row.setToCharacterKey(input.toCharacterKey());
        }
        row.setRelationshipType(input.relationshipType());
        row.setPublicRelation(input.publicRelation());
        row.setHiddenRelation(input.hiddenRelation());
        row.setDebt(input.debt());
        row.setTrust(input.trust());
        row.setIntimacy(input.intimacy());
        row.setConflict(input.conflict());
        row.setHistory(input.history());
        row.setCurrentTurn(input.currentTurn());
        row.setPayoff(input.payoff());
        row.setInitialState(input.initialState());
        row.setTargetState(input.targetState());
        row.setStatus("PLANNED");
        if (insert) {
            mapper.insertFull(row);
        } else {
            mapper.updateById(row);
        }
        return row;
    }

    public List<DeriveCharacterRelationshipDO> load(long runId) {
        return mapper.selectList(new LambdaQueryWrapper<DeriveCharacterRelationshipDO>()
                .eq(DeriveCharacterRelationshipDO::getRunId, runId)
                .orderByAsc(DeriveCharacterRelationshipDO::getId));
    }

    public List<RelationshipInput> toInputs(Collection<DeriveCharacterRelationshipDO> rows) {
        List<RelationshipInput> out = new ArrayList<>();
        for (DeriveCharacterRelationshipDO row : rows) {
            out.add(new RelationshipInput(row.getFromCharacterKey(), row.getToCharacterKey(),
                    row.getRelationshipType(), row.getPublicRelation(), row.getHiddenRelation(),
                    row.getDebt(), nullToZero(row.getTrust()), nullToZero(row.getIntimacy()),
                    nullToZero(row.getConflict()), row.getHistory(), row.getCurrentTurn(),
                    row.getPayoff(), row.getInitialState(), row.getTargetState()));
        }
        return out;
    }

    private static boolean outOfRange(Integer value) {
        return value == null || value < 0 || value > 100;
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}