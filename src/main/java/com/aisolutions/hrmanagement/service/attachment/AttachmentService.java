package com.aisolutions.hrmanagement.service.attachment;

import com.aisolutions.hrmanagement.dto.AttachmentDTO;
import com.aisolutions.hrmanagement.entity.Attachment;
import com.aisolutions.hrmanagement.repository.AttachmentRepository;
import com.aisolutions.hrmanagement.service.CurrentUserService;
import com.aisolutions.hrmanagement.service.SystemParameterService;

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

    @Inject
    SystemParameterService systemParameterService;

    @Inject
    FTPStorageService ftpStorageService;

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

    /**
     * The file's bytes. The metadata read finishes and releases its connection before
     * the FTP retrieval starts — a 20MB download must not hold one of the four pooled
     * connections for its duration.
     */
    public Uni<byte[]> downloadFile(Long uniqId) {
        if (uniqId == null)
            return Uni.createFrom().failure(new IllegalArgumentException("Attachment ID is required"));

        return attachmentRepository.findByIdWithoutData(uniqId)
            .flatMap(attachment -> {
                if (attachment == null) {
                    return Uni.createFrom().failure(
                        new RuntimeException("Attachment not found: " + uniqId));
                }
                String storageType = attachment.getStorageType();

                if ("FTP".equalsIgnoreCase(storageType)) {
                    String filePath = attachment.getFilePath();
                    if (filePath == null || filePath.isBlank()) {
                        return Uni.createFrom().failure(
                            new RuntimeException("File path not found for attachment: " + uniqId));
                    }
                    return systemParameterService.loadFtpConfig()
                        .flatMap(config -> ftpStorageService.downloadFile(filePath, config));
                }
                if ("LOCAL".equalsIgnoreCase(storageType)) {
                    return attachmentRepository.loadLocalFileData(uniqId)
                        .onItem().ifNull().failWith(() ->
                            new RuntimeException("File data not found in database: " + uniqId));
                }
                return Uni.createFrom().failure(
                    new RuntimeException("Unknown storage type: " + storageType));
            });
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

        // The FTP transfer runs BEFORE the transaction opens. Doing it inside one
        // holds a pooled DB connection (max-size 4) for the whole network round-trip,
        // so a handful of concurrent uploads would exhaust the pool.
        return currentUserService.getCurrentUser()
            .onItem().transformToUni(currentUser ->
                systemParameterService.loadFtpConfig()
                    .flatMap(config -> ftpStorageService.uploadFile(
                        fileData,
                        config.buildDirectory(moduleType, referenceCode),
                        originalName,
                        config))
                    .flatMap(remotePath ->
                        Panache.withTransaction(() ->
                            attachmentRepository.persistMetadata(
                                moduleType,
                                referenceCode,
                                originalName,
                                contentType,
                                (long) fileData.length,
                                remotePath,
                                currentUser.getStaffId()
                            )
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

    /**
     * Removes the stored file, then the row. The FTP delete runs outside the
     * transaction so the connection is not held across the network call.
     *
     * Order matters: if the FTP delete fails the row is kept, so the file stays
     * reachable and can be retried. Removing the row first would orphan the file
     * with nothing left pointing at it.
     */
    public Uni<Boolean> deleteAttachment(Long uniqId) {
        if (uniqId == null)
            return Uni.createFrom().failure(new IllegalArgumentException("Attachment ID is required"));

        return attachmentRepository.findByIdWithoutData(uniqId)
            .flatMap(attachment -> {
                if (attachment == null) {
                    return Uni.createFrom().item(false);
                }
                String storageType = attachment.getStorageType();
                String filePath = attachment.getFilePath();

                Uni<Boolean> deleteFromStorage =
                    ("FTP".equalsIgnoreCase(storageType) && filePath != null && !filePath.isBlank())
                        ? systemParameterService.loadFtpConfig()
                            .flatMap(config -> ftpStorageService.deleteFile(filePath, config))
                        : Uni.createFrom().item(true);

                return deleteFromStorage.flatMap(ignored ->
                    Panache.withTransaction(() -> attachmentRepository.deleteRow(uniqId)));
            });
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
