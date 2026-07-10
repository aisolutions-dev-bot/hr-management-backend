package com.aisolutions.hrmanagement.service.attachment;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.jboss.logging.Logger;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

/**
 * FTP storage service for HR Management attachments (staff-claim receipts).
 *
 * All credentials and path configuration are supplied at call time via
 * {@link FtpConfig}, loaded from m07SystemParameters by
 * {@link com.aisolutions.hrmanagement.service.SystemParameterService} — no
 * FTP settings live in application.properties.
 *
 * The blocking Apache Commons Net calls run on a Vert.x worker thread
 * ({@code executeBlocking}) so they never stall the reactive event loop.
 */
@ApplicationScoped
public class FTPStorageService {

    private static final Logger   LOG                = Logger.getLogger(FTPStorageService.class);
    private static final int      CONNECT_TIMEOUT_MS = 30_000;
    private static final Duration DATA_TIMEOUT       = Duration.ofSeconds(60);

    @Inject
    Vertx vertx;

    /**
     * Upload a file to FTP. Returns the full remote path of the stored file.
     *
     * @param fileData      raw bytes
     * @param directoryPath full FTP directory (built via {@link FtpConfig#buildDirectory})
     * @param originalName  original filename, used to generate a unique stored name
     * @param config        FTP credentials from m07SystemParameters
     */
    public Uni<String> uploadFile(byte[] fileData, String directoryPath, String originalName, FtpConfig config) {
        return vertx.executeBlocking(Uni.createFrom().item(() -> {
            FTPClient ftp = new FTPClient();
            try {
                connect(ftp, config);
                createDirectories(ftp, directoryPath);
                String uniqueName = generateUniqueName(originalName);
                String remotePath = directoryPath + "/" + uniqueName;

                try (InputStream is = new ByteArrayInputStream(fileData)) {
                    if (!ftp.storeFile(remotePath, is)) {
                        throw new RuntimeException("FTP storeFile failed: " + ftp.getReplyString());
                    }
                }
                LOG.info("[FTP] Upload successful");
                return remotePath;
            } catch (Exception e) {
                LOG.errorf("[FTP] Upload error: %s", e.getMessage());
                throw new RuntimeException("FTP upload failed: " + e.getMessage(), e);
            } finally {
                disconnect(ftp);
            }
        }));
    }

    /**
     * Download file bytes from FTP.
     *
     * @param remotePath full FTP path (stored in m10Attachments.FilePath)
     * @param config     FTP credentials from m07SystemParameters
     */
    public Uni<byte[]> downloadFile(String remotePath, FtpConfig config) {
        return vertx.executeBlocking(Uni.createFrom().item(() -> {
            FTPClient ftp = new FTPClient();
            try {
                connect(ftp, config);
                try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    if (!ftp.retrieveFile(remotePath, out)) {
                        throw new RuntimeException("FTP retrieveFile failed: " + ftp.getReplyString());
                    }
                    return out.toByteArray();
                }
            } catch (Exception e) {
                LOG.errorf("[FTP] Download error: %s", e.getMessage());
                throw new RuntimeException("FTP download failed: " + e.getMessage(), e);
            } finally {
                disconnect(ftp);
            }
        }));
    }

    /**
     * Delete a file from FTP. Non-existent file is treated as success.
     *
     * @param remotePath full FTP path (stored in m10Attachments.FilePath)
     * @param config     FTP credentials from m07SystemParameters
     */
    public Uni<Boolean> deleteFile(String remotePath, FtpConfig config) {
        return vertx.executeBlocking(Uni.createFrom().item(() -> {
            FTPClient ftp = new FTPClient();
            try {
                connect(ftp, config);
                boolean deleted = ftp.deleteFile(remotePath);
                LOG.infof("[FTP] Delete %s", deleted ? "successful" : "skipped (file not found)");
                return true;
            } catch (Exception e) {
                LOG.errorf("[FTP] Delete error: %s", e.getMessage());
                throw new RuntimeException("FTP delete failed: " + e.getMessage(), e);
            } finally {
                disconnect(ftp);
            }
        }));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void connect(FTPClient ftp, FtpConfig config) throws IOException {
        ftp.setConnectTimeout(CONNECT_TIMEOUT_MS);
        ftp.setDataTimeout(DATA_TIMEOUT);
        ftp.setDefaultTimeout(CONNECT_TIMEOUT_MS);
        ftp.connect(config.host(), config.port());
        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
            ftp.disconnect();
            throw new RuntimeException("FTP refused connection. Reply: " + ftp.getReplyCode());
        }
        if (!ftp.login(config.username(), config.password())) {
            ftp.disconnect();
            throw new RuntimeException("FTP login failed — check FTP credentials in m07SystemParameters");
        }
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        ftp.enterLocalPassiveMode();
        LOG.info("[FTP] Connected");
    }

    private void disconnect(FTPClient ftp) {
        try {
            if (ftp.isConnected()) {
                ftp.logout();
                ftp.disconnect();
                LOG.info("[FTP] Disconnected");
            }
        } catch (IOException ignored) {}
    }

    private void createDirectories(FTPClient ftp, String path) throws IOException {
        StringBuilder current = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            current.append("/").append(segment);
            if (!ftp.changeWorkingDirectory(current.toString())) {
                ftp.makeDirectory(current.toString());
                ftp.changeWorkingDirectory(current.toString());
            }
        }
        ftp.changeWorkingDirectory("/");
    }

    private String generateUniqueName(String originalName) {
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        int dot = originalName.lastIndexOf('.');
        if (dot > 0) {
            return uuid + "-" + sanitize(originalName.substring(0, dot)) + originalName.substring(dot);
        }
        return uuid + "-" + sanitize(originalName);
    }

    private String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
