package com.aisolutions.hrmanagement.service.attachment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Service for handling file storage via FTP.
 *
 * Files stored under: {basePath}/{moduleType}/{referenceCode}/{uniqueFileName}
 * Example: /pms-attachments/STAFFCLAIMDET/123/a1b2c3d4-receipt.jpg
 */
@ApplicationScoped
public class FTPStorageService {

    @ConfigProperty(name = "ftp.host")
    String host;

    @ConfigProperty(name = "ftp.port", defaultValue = "21")
    int port;

    @ConfigProperty(name = "ftp.username")
    String username;

    @ConfigProperty(name = "ftp.password")
    String password;

    @ConfigProperty(name = "ftp.base-path", defaultValue = "/pms-attachments")
    String basePath;

    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final Duration DATA_TIMEOUT = Duration.ofSeconds(60);

    public Uni<String> uploadFile(byte[] fileData, String moduleType, String referenceCode, String originalName) {
        return Uni.createFrom().item(() -> {
            FTPClient ftpClient = new FTPClient();
            try {
                connect(ftpClient);
                String directoryPath = buildDirectoryPath(moduleType, referenceCode);
                createDirectories(ftpClient, directoryPath);
                String uniqueFileName = generateUniqueFileName(originalName);
                String remotePath = directoryPath + "/" + uniqueFileName;

                try (InputStream inputStream = new ByteArrayInputStream(fileData)) {
                    boolean success = ftpClient.storeFile(remotePath, inputStream);
                    if (!success) {
                        throw new RuntimeException("Failed to upload file. FTP reply: " + ftpClient.getReplyString());
                    }
                }
                System.out.println("File uploaded successfully to: " + remotePath);
                return remotePath;
            } catch (Exception e) {
                System.err.println("FTP upload error: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to upload file to FTP server: " + e.getMessage(), e);
            } finally {
                disconnect(ftpClient);
            }
        });
    }

    public Uni<byte[]> downloadFile(String remotePath) {
        return Uni.createFrom().item(() -> {
            FTPClient ftpClient = new FTPClient();
            try {
                connect(ftpClient);
                try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                    boolean success = ftpClient.retrieveFile(remotePath, outputStream);
                    if (!success) {
                        throw new RuntimeException("Failed to download file. FTP reply: " + ftpClient.getReplyString());
                    }
                    return outputStream.toByteArray();
                }
            } catch (Exception e) {
                System.err.println("FTP download error: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to download file from FTP server: " + e.getMessage(), e);
            } finally {
                disconnect(ftpClient);
            }
        });
    }

    public Uni<Boolean> deleteFile(String remotePath) {
        return Uni.createFrom().item(() -> {
            FTPClient ftpClient = new FTPClient();
            try {
                connect(ftpClient);
                boolean deleted = ftpClient.deleteFile(remotePath);
                if (deleted) {
                    System.out.println("File deleted successfully: " + remotePath);
                } else {
                    System.out.println("File not found or already deleted: " + remotePath);
                }
                return true;
            } catch (Exception e) {
                System.err.println("FTP delete error: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Failed to delete file from FTP server: " + e.getMessage(), e);
            } finally {
                disconnect(ftpClient);
            }
        });
    }

    public Uni<Boolean> fileExists(String remotePath) {
        return Uni.createFrom().item(() -> {
            FTPClient ftpClient = new FTPClient();
            try {
                connect(ftpClient);
                try (InputStream is = ftpClient.retrieveFileStream(remotePath)) {
                    boolean exists = is != null && ftpClient.getReplyCode() != 550;
                    ftpClient.completePendingCommand();
                    return exists;
                }
            } catch (Exception e) {
                return false;
            } finally {
                disconnect(ftpClient);
            }
        });
    }

    private void connect(FTPClient ftpClient) throws IOException {
        ftpClient.setConnectTimeout(CONNECT_TIMEOUT_MS);
        ftpClient.setDataTimeout(DATA_TIMEOUT);
        ftpClient.setDefaultTimeout(CONNECT_TIMEOUT_MS);

        ftpClient.connect(host, port);
        int replyCode = ftpClient.getReplyCode();
        if (!FTPReply.isPositiveCompletion(replyCode)) {
            ftpClient.disconnect();
            throw new RuntimeException("FTP server refused connection. Reply code: " + replyCode);
        }

        boolean loggedIn = ftpClient.login(username, password);
        if (!loggedIn) {
            ftpClient.disconnect();
            throw new RuntimeException("Failed to login to FTP server. Check username/password.");
        }

        ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
        ftpClient.enterLocalPassiveMode();

        System.out.println("Connected to FTP server: " + host);
    }

    private String buildDirectoryPath(String moduleType, String referenceCode) {
        return basePath + "/" + moduleType.toUpperCase() + "/" + referenceCode;
    }

    private String generateUniqueFileName(String originalName) {
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        int lastDot = originalName.lastIndexOf('.');
        if (lastDot > 0) {
            String name = originalName.substring(0, lastDot);
            String ext = originalName.substring(lastDot);
            name = sanitizeFileName(name);
            return uuid + "-" + name + ext;
        }
        return uuid + "-" + sanitizeFileName(originalName);
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    private void createDirectories(FTPClient ftpClient, String directoryPath) throws IOException {
        String[] folders = directoryPath.split("/");
        StringBuilder currentPath = new StringBuilder();

        for (String folder : folders) {
            if (folder.isEmpty()) continue;
            currentPath.append("/").append(folder);
            String path = currentPath.toString();
            boolean exists = ftpClient.changeWorkingDirectory(path);
            if (!exists) {
                boolean created = ftpClient.makeDirectory(path);
                if (created) {
                    System.out.println("Created directory: " + path);
                }
                ftpClient.changeWorkingDirectory(path);
            }
        }
        ftpClient.changeWorkingDirectory("/");
    }

    private void disconnect(FTPClient ftpClient) {
        try {
            if (ftpClient.isConnected()) {
                ftpClient.logout();
                ftpClient.disconnect();
            }
        } catch (IOException e) {
            System.err.println("Error disconnecting from FTP: " + e.getMessage());
        }
    }
}
