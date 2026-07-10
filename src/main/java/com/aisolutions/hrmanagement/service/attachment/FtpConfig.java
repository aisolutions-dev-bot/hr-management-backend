package com.aisolutions.hrmanagement.service.attachment;

/**
 * Immutable snapshot of FTP connection settings and path configuration,
 * loaded from m07SystemParameters at runtime by
 * {@link com.aisolutions.hrmanagement.service.SystemParameterService}.
 *
 * Parameters used:
 *   FTP-HOST            → host
 *   FTP-USERNAME        → username
 *   FTP-PASSWORD        → password
 *   ATTACHMENT-MAIN-URL → mainUrl (e.g. /<attachment-base>)
 *   ATTACHMENT-PATH-HR  → folder  (module folder, e.g. hr-attachments;
 *                          defaults to "hr-attachments" when absent)
 *
 * Resulting remote directory per attachment ({module}/{sub-module}/{reference}):
 *   {mainUrl}/{folder}/{MODULETYPE}/{referenceCode}
 * Example:
 *   /<attachment-base>/hr-attachments/CLAIM/123
 */
public record FtpConfig(
    String host,
    int    port,
    String username,
    String password,
    String mainUrl,
    String folder
) {
    /** Full remote directory for an attachment. */
    public String buildDirectory(String moduleType, String referenceCode) {
        return mainUrl + "/" + folder + "/" + moduleType.toUpperCase() + "/" + referenceCode;
    }
}
