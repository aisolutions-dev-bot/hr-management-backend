package com.aisolutions.hrmanagement.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "m10Attachments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "UniqId")
    private Long uniqId;

    @Column(name = "ModuleType", length = 50, nullable = false)
    private String moduleType;

    @Column(name = "ReferenceCode", length = 50, nullable = false)
    private String referenceCode;

    @Column(name = "FileName", length = 100, nullable = false)
    private String fileName;

    @Column(name = "OriginalName", length = 255, nullable = false)
    private String originalName;

    @Column(name = "FileSize", nullable = false)
    private Long fileSize;

    @Column(name = "StorageType", length = 20)
    private String storageType;

    @Column(name = "ContentType", length = 100)
    private String contentType;

    @Column(name = "FileExtension", length = 20)
    private String fileExtension;

    @Column(name = "FilePath", length = 500)
    private String filePath;

    @Lob
    @Column(name = "FileData", columnDefinition = "LONGBLOB")
    private byte[] fileData;

    @Column(name = "Description", length = 500)
    private String description;

    @Column(name = "UploadSource", length = 20)
    private String uploadSource = "WEB";

    @Column(name = "EntryStaff", length = 50)
    private String entryStaff;

    @Column(name = "EntryDate")
    private LocalDateTime entryDate;

    @Column(name = "LastEditStaff", length = 50)
    private String lastEditStaff;

    @Column(name = "LastEditDate")
    private LocalDateTime lastEditDate;
}
