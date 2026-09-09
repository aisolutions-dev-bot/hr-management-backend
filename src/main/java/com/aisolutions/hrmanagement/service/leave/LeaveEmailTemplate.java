package com.aisolutions.hrmanagement.service.leave;

/**
 * HTML email template for the leave-application notification sent to the approver.
 * Inline styles only (email clients strip &lt;style&gt; tags).
 */
public final class LeaveEmailTemplate {

    private static final String COLOR_PRIMARY = "#7c3aed";
    private static final String COLOR_PRIMARY_SOFT = "#ede9fe";
    private static final String COLOR_BG = "#f8fafc";
    private static final String COLOR_BORDER = "#e2e8f0";
    private static final String COLOR_TEXT = "#0f172a";
    private static final String COLOR_TEXT_MUTED = "#64748b";
    private static final String COLOR_TEXT_LIGHT = "#94a3b8";

    private LeaveEmailTemplate() {
    }

    /**
     * Builds the email sent to the approver when a staff member applies for — or requests
     * cancellation of — a leave.
     *
     * @param approverName who the email greets (falls back to the approver id upstream)
     * @param applicantName the staff member who submitted
     * @param leaveType     the leave type code/description
     * @param period        human-readable period text (e.g. " from 2026-09-10 to 2026-09-12"), may be blank
     * @param cancel        true for a cancellation request, false for a new application
     * @param remarks       the applicant's remarks, may be blank
     */
    public static String buildSubmittedEmail(String approverName, String applicantName, String leaveType,
                                             String period, boolean cancel, String remarks) {
        String title = cancel ? "Leave Cancellation Request" : "New Leave Application";
        String intro = "<strong>" + escHtml(applicantName) + "</strong>"
                + (cancel ? " has requested to cancel " : " has applied for ")
                + escHtml(leaveType) + " leave"
                + (period != null && !period.isBlank() ? escHtml(period) : "") + ".";

        StringBuilder rows = new StringBuilder();
        rows.append("<table cellpadding='0' cellspacing='0' style='width:100%;border-collapse:collapse;'>");
        rows.append(detailRow("Staff", applicantName));
        rows.append(detailRow("Leave Type", leaveType));
        rows.append(detailRow("Period", (period != null ? period.trim() : "")));
        rows.append(detailRow("Remarks", remarks));
        rows.append("</table>");

        return header(title)
                + greeting("Hi " + approverName)
                + paragraph(intro)
                + detailCard(rows.toString())
                + paragraph("Please log in to the HR portal to review and approve this request.")
                + footer();
    }

    private static String header(String title) {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'></head>"
                + "<body style='margin:0;padding:0;background-color:" + COLOR_BG + ";font-family:Arial,sans-serif;'>"
                + "<div style='background-color:" + COLOR_PRIMARY + ";padding:20px;text-align:center;'>"
                + "<h1 style='color:#ffffff;margin:0;font-size:20px;'>" + escHtml(title) + "</h1></div>"
                + "<div style='max-width:600px;margin:0 auto;padding:24px;'>";
    }

    private static String footer() {
        return "</div><div style='max-width:600px;margin:0 auto;padding:16px 24px;text-align:center;color:"
                + COLOR_TEXT_LIGHT + ";font-size:12px;border-top:1px solid " + COLOR_BORDER + ";'>"
                + "<p style='margin:0;'>This is an automated notification from AI Solutions PL. "
                + "Please do not reply to this email.</p></div></body></html>";
    }

    private static String greeting(String text) {
        return "<p style='color:" + COLOR_TEXT + ";font-size:14px;line-height:1.5;'>" + escHtml(text) + ",</p>";
    }

    private static String paragraph(String html) {
        return "<p style='color:" + COLOR_TEXT + ";font-size:14px;line-height:1.5;'>" + html + "</p>";
    }

    private static String detailCard(String content) {
        return "<table cellpadding='0' cellspacing='0' style='width:100%;border-collapse:collapse;margin:16px 0;'>"
                + "<tr><td style='border:2px dashed " + COLOR_BORDER + ";border-left:4px solid " + COLOR_PRIMARY
                + ";background-color:" + COLOR_PRIMARY_SOFT + ";padding:16px;border-radius:4px;'>"
                + content + "</td></tr></table>";
    }

    private static String detailRow(String label, String value) {
        if (value == null || value.isBlank()) return "";
        return "<tr><td style='color:" + COLOR_TEXT_MUTED + ";font-size:12px;padding:4px 8px 4px 0;"
                + "white-space:nowrap;vertical-align:top;width:110px;'>" + escHtml(label) + "</td>"
                + "<td style='color:" + COLOR_TEXT + ";font-size:14px;padding:4px 0;'>" + escHtml(value) + "</td></tr>";
    }

    private static String escHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
