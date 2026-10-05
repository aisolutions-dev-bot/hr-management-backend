package com.aisolutions.hrmanagement.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class LeaveBalanceDTONativeHintsTest {

    @Test
    void registersLeaveBalanceForNativeJsonSerialization() {
        assertNotNull(LeaveBalanceDTO.class.getAnnotation(RegisterForReflection.class));
    }
}
