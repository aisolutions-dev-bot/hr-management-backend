package com.aisolutions.hrmanagement.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DropdownResponseDTO {

    private List<DropdownOptionDTO> projects;    
}
