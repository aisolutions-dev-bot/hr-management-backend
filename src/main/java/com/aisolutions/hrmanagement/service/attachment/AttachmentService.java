package com.aisolutions.hrmanagement.service.attachment;

import com.aisolutions.hrmanagement.dto.AttachmentDTO;
import com.aisolutions.hrmanagement.entity.Attachment;
import com.aisolutions.hrmanagement.repository.AttachmentRepository;
import com.aisolutions.hrmanagement.service.CurrentUserService;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class AttachmentService {

    @Inject
    AttachmentRepository attachmentRepository;

    @Inject
    CurrentUserService currentUserService;

    private static final List<String> ALLOWED_EXTENSIONS = List.of(
        ".pdf", ".doc", ".docx", ".xls", ".xlsx",
        ".jpg", ".jpeg", ".png", ".gif", ".txt"
    );

    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024;

    public Uni<List<AttachmentDTO>> getAttachments(String moduleType, String referenceCode) {
        if (moduleType == null || moduleType.isBlank())
            return Uni.createFrom().failure(new IllegalArgumentException("Module type is required"));
        if (referenceCode == null || referenceCode.isBlank())
            return Uni.createFrom().failure(new IllegalArgumentException("Reference code is required"));
        return attachmentRepository.findByModuleAndReference(
            moduleType.toUpperCase(),
            referenceCode.toUpperCase()
        );
    }

    public Uni<Attachment> getAttachmentById(Long uniqId) {
        return attachmentRepository.findByIdWithoutData(uniqId);
    }

    public Uni<byte[]> downloadFile(Long uniqId) {
        if (uniqId == null)
            return Uni.createFrom().failure(new IllegalArgumentException("Attachment ID is required"));
        return attachmentRepository.downloadFileContent(uniqId);
    }

    public Uni<Attachment> getAttachmentForDownload(Long uniqId) {
        return attachmentRepository.findByIdWithoutData(uniqId);
    }

    public Uni<AttachmentDTO> uploadFile(
            String moduleType,
            String referenceCode,
            String originalName,
            String contentType,
            byte[] fileData) {

        String validationError = validateUpload(moduleType, referenceCode, originalName, fileData);
        if (validationError != null) {
            return Uni.createFrom().failure(new IllegalArgumentException(validationError));
        }

        return currentUserService.getCurrentUser()
            .onItem().transformToUni(currentUser ->
                Panache.withTransaction(() ->
                    attachmentRepository.createAttachment(
                        moduleType,
                        referenceCode,
                        originalName,
                        contentType,
                        (long) fileData.length,
                        fileData,
                        currentUser.getStaffId()
                    )
                )
            )
            .onItem().transform(this::mapEntityToDTO);
    }

    private String validateUpload(String moduleType, String referenceCode,
                                   String originalName, byte[] fileData) {
        if (moduleType == null || moduleType.isBlank()) return "Module type is required";
        if (referenceCode == null || referenceCode.isBlank()) return "Reference code is required";
        if (originalName == null || originalName.isBlank()) return "File name is required";
        if (fileData == null || fileData.length == 0) return "File data is empty";
        String extension = getFileExtension(originalName).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return "File type not allowed: " + extension +
                ". Allowed types: " + String.join(", ", ALLOWED_EXTENSIONS);
        }
        if (fileData.length > MAX_FILE_SIZE) {
            return "File size exceeds maximum of " + (MAX_FILE_SIZE / 1024 / 1024) + "MB";
        }
        return null;
    }

    public Uni<Boolean> deleteAttachment(Long uniqId) {
        if (uniqId == null)
            return Uni.createFrom().failure(new IllegalArgumentException("Attachment ID is required"));
        return Panache.withTransaction(() -> attachmentRepository.deleteAttachment(uniqId));
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isBlank()) return "";
        int lastDot = filename.lastIndexOf('.');
        return lastDot != -1 ? filename.substring(lastDot) : "";
    }

    private AttachmentDTO mapEntityToDTO(Attachment entity) {
        return new AttachmentDTO(
            entity.getUniqId(),
            entity.getModuleType(),
            entity.getReferenceCode(),
            entity.getFileName(),
            entity.getOriginalName(),
            entity.getFileSize(),
            entity.getStorageType(),
            entity.getContentType(),
            entity.getFileExtension(),
            entity.getFilePath(),
            entity.getDescription(),
            entity.getUploadSource(),
            entity.getEntryStaff(),
            entity.getEntryDate()
        );
    }
}
