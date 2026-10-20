package com.aisolutions.hrmanagement.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A project as the organization API lists it in its dropdown. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class ProjectOptionDTO {

    private String projectCode;
    private String projectName;
}
