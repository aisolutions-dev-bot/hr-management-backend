package com.aisolutions.hrmanagement.dto;

import java.util.List;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@RegisterForReflection
public class DropdownResponseDTO {

    private List<DropdownOptionDTO> projects;
}
