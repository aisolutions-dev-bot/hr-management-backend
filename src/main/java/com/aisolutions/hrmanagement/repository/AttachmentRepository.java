package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.dto.AttachmentDTO;
import com.aisolutions.hrmanagement.entity.Attachment;
import com.aisolutions.hrmanagement.service.SystemParameterService;
import com.aisolutions.hrmanagement.service.attachment.FTPStorageService;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@WithSession
public class AttachmentRepository implements PanacheRepositoryBase<Attachment, Long> {

    @Inject
    FTPStorageService ftpStorageService;

    @Inject
    SystemParameterService systemParameterService;

    public Uni<List<AttachmentDTO>> findByModuleAndReference(String moduleType, String referenceCode) {
        return getSession().flatMap(session ->
            session.createQuery(
                "SELECT new com.aisolutions.hrmanagement.dto.AttachmentDTO(" +
                    "a.uniqId, a.moduleType, a.referenceCode, a.fileName, a.originalName, " +
                    "a.fileSize, a.storageType, a.contentType, a.fileExtension, a.filePath, " +
                    "a.description, a.uploadSource, a.entryStaff, a.entryDate) " +
                    "FROM Attachment a " +
                    "WHERE a.moduleType = :moduleType AND a.referenceCode = :referenceCode " +
                    "ORDER BY a.entryDate DESC",
                AttachmentDTO.class)
                .setParameter("moduleType", moduleType)
                .setParameter("referenceCode", referenceCode)
                .getResultList()
            )
            .onFailure().invoke(e -> {
                System.err.println("Error fetching attachments: " + e.getMessage());
                e.printStackTrace();
            });
    }

    public Uni<Attachment> findByIdWithoutData(Long uniqId) {
        return getSession().flatMap(session -> session.find(Attachment.class, uniqId));
    }

    public Uni<Long> countByModuleAndReference(String moduleType, String referenceCode) {
        return getSession().flatMap(session -> session.createQuery(
            "SELECT COUNT(a) FROM Attachment a " +
                "WHERE a.moduleType = :moduleType AND a.referenceCode = :referenceCode",
            Long.class)
            .setParameter("moduleType", moduleType)
            .setParameter("referenceCode", referenceCode)
            .getSingleResult());
    }

    public Uni<Attachment> createAttachment(
            String moduleType,
            String referenceCode,
            String originalName,
            String contentType,
            Long fileSize,
            byte[] fileData,
            String currentUser) {

        return systemParameterService.loadFtpConfig()
            .flatMap(config -> ftpStorageService.uploadFile(
                fileData, config.buildDirectory(moduleType, referenceCode), originalName, config))
            .flatMap(remotePath -> getSession().flatMap(session -> {
                Attachment entity = new Attachment();
                String extension = getFileExtension(originalName);
                String uniqueFileName = UUID.randomUUID() + extension;

                entity.setModuleType(moduleType.toUpperCase());
                entity.setReferenceCode(referenceCode.toUpperCase());
                entity.setFileName(uniqueFileName);
                entity.setOriginalName(originalName);
                entity.setFileSize(fileSize);
                entity.setContentType(contentType);
                entity.setFileExtension(extension);
                entity.setStorageType("FTP");
                entity.setFilePath(remotePath);
                entity.setFileData(null);
                entity.setUploadSource("WEB");
                entity.setEntryStaff(currentUser);
                entity.setEntryDate(LocalDateTime.now());

                return session.persist(entity).replaceWith(entity);
            }))
            .onFailure().invoke(e -> {
                System.err.println("Error creating attachment: " + e.getMessage());
                e.printStackTrace();
            });
    }

    public Uni<byte[]> downloadFileContent(Long uniqId) {
        return findByIdWithoutData(uniqId)
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
                } else if ("LOCAL".equalsIgnoreCase(storageType)) {
                    byte[] fileData = attachment.getFileData();
                    if (fileData == null) {
                        return Uni.createFrom().failure(
                            new RuntimeException("File data not found in database: " + uniqId));
                    }
                    return Uni.createFrom().item(fileData);
                } else {
                    return Uni.createFrom().failure(
                        new RuntimeException("Unknown storage type: " + storageType));
                }
            });
    }

    public Uni<Boolean> deleteAttachment(Long uniqId) {
        return findByIdWithoutData(uniqId)
            .flatMap(attachment -> {
                if (attachment == null) {
                    return Uni.createFrom().item(false);
                }
                String storageType = attachment.getStorageType();
                String filePath = attachment.getFilePath();

                Uni<Boolean> deleteFromStorage;
                if ("FTP".equalsIgnoreCase(storageType) && filePath != null && !filePath.isBlank()) {
                    deleteFromStorage = systemParameterService.loadFtpConfig()
                        .flatMap(config -> ftpStorageService.deleteFile(filePath, config));
                } else {
                    deleteFromStorage = Uni.createFrom().item(true);
                }

                return deleteFromStorage
                    .flatMap(deleted -> getSession().flatMap(session ->
                        session.find(Attachment.class, uniqId)
                            .onItem().ifNotNull().transformToUni(entity -> session.remove(entity).replaceWith(true))
                            .onItem().ifNull().continueWith(false)));
            });
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isBlank()) return "";
        int lastDot = filename.lastIndexOf('.');
        return lastDot != -1 ? filename.substring(lastDot) : "";
    }
}
