package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.dto.AttachmentDTO;
import com.aisolutions.hrmanagement.entity.Attachment;
import com.aisolutions.shared.util.DateUtil;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

/**
 * Database access only. FTP transfers live in AttachmentService: this class is
 * @WithSession, so any network call made here would hold a pooled connection
 * for its duration.
 */
@ApplicationScoped
@WithSession
public class AttachmentRepository implements PanacheRepositoryBase<Attachment, Long> {

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

    /**
     * Records an already-uploaded file's metadata. The FTP transfer is deliberately
     * NOT done here: this runs inside a transaction, and holding a pooled connection
     * across a network transfer drains the pool (max-size 4) under concurrent upload.
     * The caller transfers the file first and passes the resulting {@code remotePath}.
     */
    public Uni<Attachment> persistMetadata(
            String moduleType,
            String referenceCode,
            String originalName,
            String contentType,
            Long fileSize,
            String remotePath,
            String currentUser) {

        return getSession().flatMap(session -> {
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
                entity.setEntryDate(DateUtil.nowSGT());

                return session.persist(entity).replaceWith(entity);
            })
            .onFailure().invoke(e -> {
                System.err.println("Error creating attachment: " + e.getMessage());
                e.printStackTrace();
            });
    }

    /** The row only. FTP retrieval is the caller's job, outside the session. */
    public Uni<byte[]> loadLocalFileData(Long uniqId) {
        return getSession().flatMap(session ->
            session.createQuery("SELECT a.fileData FROM Attachment a WHERE a.uniqId = :id", byte[].class)
                .setParameter("id", uniqId)
                .getResultList()
        ).map(list -> list.isEmpty() ? null : list.get(0));
    }

    /** Removes the row. The stored file is deleted by the caller, before this runs. */
    public Uni<Boolean> deleteRow(Long uniqId) {
        return getSession().flatMap(session ->
            session.find(Attachment.class, uniqId)
                .onItem().ifNotNull().transformToUni(entity -> session.remove(entity).replaceWith(true))
                .onItem().ifNull().continueWith(false));
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isBlank()) return "";
        int lastDot = filename.lastIndexOf('.');
        return lastDot != -1 ? filename.substring(lastDot) : "";
    }
}
