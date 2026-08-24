package com.aisolutions.hrmanagement.repository;

import com.aisolutions.hrmanagement.dto.AttachmentDTO;
import com.aisolutions.hrmanagement.entity.Attachment;
import com.aisolutions.shared.util.DateUtil;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.mysqlclient.MySQLClient;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.SqlClient;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Slf4j
public class AttachmentRepository {

    public Uni<List<AttachmentDTO>> findByModuleAndReference(SqlClient client, String moduleType, String referenceCode) {
        return client.preparedQuery(
                "SELECT UniqId, ModuleType, ReferenceCode, FileName, OriginalName, FileSize, " +
                "StorageType, ContentType, FileExtension, FilePath, Description, UploadSource, " +
                "EntryStaff, EntryDate " +
                "FROM m10Attachments WHERE ModuleType = ? AND ReferenceCode = ? ORDER BY EntryDate DESC")
            .execute(Tuple.tuple().addValue(moduleType).addValue(referenceCode))
            .map(rows -> {
                List<AttachmentDTO> result = new ArrayList<>();
                for (Row row : rows) {
                    result.add(toDto(row));
                }
                return result;
            })
            .onFailure().invoke(e -> {
                System.err.println("Error fetching attachments: " + e.getMessage());
                e.printStackTrace();
            });
    }

    public Uni<Attachment> findByIdWithoutData(SqlClient client, Long uniqId) {
        return client.preparedQuery(
                "SELECT UniqId, ModuleType, ReferenceCode, FileName, OriginalName, FileSize, " +
                "StorageType, ContentType, FileExtension, FilePath, Description, UploadSource, " +
                "EntryStaff, EntryDate " +
                "FROM m10Attachments WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(uniqId))
            .map(rows -> rows.iterator().hasNext() ? toEntity(rows.iterator().next()) : null);
    }

    public Uni<Long> countByModuleAndReference(SqlClient client, String moduleType, String referenceCode) {
        return client.preparedQuery(
                "SELECT COUNT(*) AS cnt FROM m10Attachments WHERE ModuleType = ? AND ReferenceCode = ?")
            .execute(Tuple.tuple().addValue(moduleType).addValue(referenceCode))
            .map(rows -> rows.iterator().next().getLong("cnt"));
    }

    public Uni<Attachment> persistMetadata(
            SqlClient client,
            String moduleType,
            String referenceCode,
            String originalName,
            String contentType,
            Long fileSize,
            String remotePath,
            String currentUser) {

        String extension = getFileExtension(originalName);
        String uniqueFileName = UUID.randomUUID() + extension;

        return client.preparedQuery(
                "INSERT INTO m10Attachments (ModuleType, ReferenceCode, FileName, OriginalName, " +
                "FileSize, ContentType, FileExtension, StorageType, FilePath, UploadSource, " +
                "EntryStaff, EntryDate) VALUES (?, ?, ?, ?, ?, ?, ?, 'FTP', ?, 'WEB', ?, ?)")
            .execute(Tuple.tuple()
                .addValue(moduleType.toUpperCase())
                .addValue(referenceCode.toUpperCase())
                .addValue(uniqueFileName)
                .addValue(originalName)
                .addValue(fileSize)
                .addValue(contentType)
                .addValue(extension)
                .addValue(remotePath)
                .addValue(currentUser)
                .addValue(DateUtil.nowSGT()))
            .flatMap(result -> {
                Long id = result.property(MySQLClient.LAST_INSERTED_ID);
                Attachment entity = new Attachment();
                entity.setUniqId(id);
                entity.setModuleType(moduleType.toUpperCase());
                entity.setReferenceCode(referenceCode.toUpperCase());
                entity.setFileName(uniqueFileName);
                entity.setOriginalName(originalName);
                entity.setFileSize(fileSize);
                entity.setContentType(contentType);
                entity.setFileExtension(extension);
                entity.setStorageType("FTP");
                entity.setFilePath(remotePath);
                entity.setUploadSource("WEB");
                entity.setEntryStaff(currentUser);
                entity.setEntryDate(DateUtil.nowSGT());
                return Uni.createFrom().item(entity);
            })
            .onFailure().invoke(e -> {
                System.err.println("Error creating attachment: " + e.getMessage());
                e.printStackTrace();
            });
    }

    /** The row only. FTP retrieval is the caller's job. */
    public Uni<byte[]> loadLocalFileData(SqlClient client, Long uniqId) {
        return client.preparedQuery("SELECT FileData FROM m10Attachments WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(uniqId))
            .map(rows -> {
                if (!rows.iterator().hasNext()) return null;
                io.vertx.mutiny.core.buffer.Buffer buf = rows.iterator().next().getBuffer("FileData");
                return buf != null ? buf.getDelegate().getBytes() : null;
            });
    }

    /** Removes the row. The stored file is deleted by the caller, before this runs. */
    public Uni<Boolean> deleteRow(SqlClient client, Long uniqId) {
        return client.preparedQuery("DELETE FROM m10Attachments WHERE UniqId = ?")
            .execute(Tuple.tuple().addValue(uniqId))
            .map(result -> result.rowCount() > 0);
    }

    private String getFileExtension(String filename) {
        if (filename == null || filename.isBlank()) return "";
        int lastDot = filename.lastIndexOf('.');
        return lastDot != -1 ? filename.substring(lastDot) : "";
    }

    private AttachmentDTO toDto(Row row) {
        return new AttachmentDTO(
            row.getLong("UniqId"),
            row.getString("ModuleType"),
            row.getString("ReferenceCode"),
            row.getString("FileName"),
            row.getString("OriginalName"),
            row.getLong("FileSize"),
            row.getString("StorageType"),
            row.getString("ContentType"),
            row.getString("FileExtension"),
            row.getString("FilePath"),
            row.getString("Description"),
            row.getString("UploadSource"),
            row.getString("EntryStaff"),
            row.getLocalDateTime("EntryDate")
        );
    }

    private Attachment toEntity(Row row) {
        Attachment a = new Attachment();
        a.setUniqId(row.getLong("UniqId"));
        a.setModuleType(row.getString("ModuleType"));
        a.setReferenceCode(row.getString("ReferenceCode"));
        a.setFileName(row.getString("FileName"));
        a.setOriginalName(row.getString("OriginalName"));
        a.setFileSize(row.getLong("FileSize"));
        a.setStorageType(row.getString("StorageType"));
        a.setContentType(row.getString("ContentType"));
        a.setFileExtension(row.getString("FileExtension"));
        a.setFilePath(row.getString("FilePath"));
        a.setDescription(row.getString("Description"));
        a.setUploadSource(row.getString("UploadSource"));
        a.setEntryStaff(row.getString("EntryStaff"));
        a.setEntryDate(row.getLocalDateTime("EntryDate"));
        return a;
    }
}
