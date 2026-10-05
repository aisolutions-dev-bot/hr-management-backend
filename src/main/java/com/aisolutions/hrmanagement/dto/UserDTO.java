package com.aisolutions.hrmanagement.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class UserDTO {
    private String staffId;
    private String secLoginId;
    private boolean secChangePassword;
}
