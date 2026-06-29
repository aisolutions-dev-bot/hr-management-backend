package com.aisolutions.hrmanagement.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentDTO {

    private Long uniqId;
    private String moduleType;
    private String referenceCode;
    private String fileName;
    private String originalName;
    private Long fileSize;
    private String storageType;
    private String contentType;
    private String fileExtension;
    private String filePath;
    private String description;
    private String uploadSource;
    private String entryStaff;
    private LocalDateTime entryDate;
}
