package com.zzdzz.novelgen.llm.contract;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeOutputContractRegistryTest {

    @Test
    void requireReturnsRegisteredContract() {
        NodeOutputContractRegistry registry = new NodeOutputContractRegistry();
        NodeOutputContract<String> contract = contract("derive.route_narrative", 2);

        registry.register(contract);

        assertSame(contract, registry.require("derive.route_narrative", 2));
    }

    @Test
    void requireFailsWithUnknownContractId() {
        NodeOutputContractRegistry registry = new NodeOutputContractRegistry();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> registry.require("derive.unknown", 1));

        assertTrue(error.getMessage().contains("derive.unknown@1"));
    }

    @Test
    void duplicateContractIdAndVersionFailsAtStartup() {
        NodeOutputContractRegistry registry = new NodeOutputContractRegistry();
        NodeOutputContract<String> first = contract("derive.route_narrative", 2);
        NodeOutputContract<String> duplicate = contract("derive.route_narrative", 2);
        registry.register(first);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> registry.register(duplicate));

        assertTrue(error.getMessage().contains("derive.route_narrative@2"));
    }

    @Test
    void allReturnsRegistrationOrder() {
        NodeOutputContractRegistry registry = new NodeOutputContractRegistry();
        NodeOutputContract<String> first = contract("derive.route_narrative", 2);
        NodeOutputContract<String> second = contract("derive.head_c", 1);
        registry.register(first);
        registry.register(second);

        assertEquals(List.of(first, second), List.copyOf(registry.all()));
    }

    private NodeOutputContract<String> contract(String contractId, int schemaVersion) {
        return new NodeOutputContract<>(
                contractId,
                "derive_plan",
                schemaVersion,
                JsonNodeFactory.instance.objectNode(),
                "prompt",
                node -> "parsed",
                value -> List.of());
    }
}