package com.aisolutions.hrmanagement.service.staffclaim;

/**
 * HTML email template for the claim-submission notification sent to the HR approver.
 * Inline styles only (email clients strip &lt;style&gt; tags). Mirrors LeaveEmailTemplate's
 * house style (violet header, dashed detail card).
 */
public final class ClaimEmailTemplate {

    private static final String COLOR_PRIMARY = "#7c3aed";
    private static final String COLOR_PRIMARY_SOFT = "#ede9fe";
    private static final String COLOR_BG = "#f8fafc";
    private static final String COLOR_BORDER = "#e2e8f0";
    private static final String COLOR_TEXT = "#0f172a";
    private static final String COLOR_TEXT_MUTED = "#64748b";
    private static final String COLOR_TEXT_LIGHT = "#94a3b8";
    private static final String COLOR_APPROVED = "#15803d";

    private ClaimEmailTemplate() {
    }

    /**
     * Builds the email sent to the HR approver when a staff member submits a claim.
     *
     * @param approverName who the email greets (falls back to the approver id upstream)
     * @param claimantName the staff member who submitted
     * @param period       the claim period, e.g. "JULY-2026"
     * @param amount       formatted total amount, e.g. "SGD 128.00"
     */
    public static String buildSubmittedEmail(String approverName, String claimantName, String period,
                                              String amount) {
        String intro = "<strong>" + escHtml(claimantName) + "</strong> has submitted a staff claim for "
                + escHtml(period) + ".";

        StringBuilder rows = new StringBuilder();
        rows.append("<table cellpadding='0' cellspacing='0' style='width:100%;border-collapse:collapse;'>");
        rows.append(detailRow("Staff", claimantName));
        rows.append(detailRow("Claim Period", period));
        rows.append(detailRow("Amount", amount));
        rows.append("</table>");

        return header("New Staff Claim Submitted")
                + greeting("Hi " + approverName)
                + paragraph(intro)
                + detailCard(rows.toString())
                + paragraph("Please log in to the HR portal to review and approve this claim.")
                + footer();
    }

    /**
     * Builds the email sent to the HR approver when a staff member resubmits a previously
     * rejected receipt for review.
     *
     * @param approverName who the email greets (falls back to the approver id upstream)
     * @param claimantName the staff member who resubmitted
     * @param period       the claim period, e.g. "JULY-2026"
     */
    public static String buildResubmittedEmail(String approverName, String claimantName, String period) {
        String intro = "<strong>" + escHtml(claimantName) + "</strong> has resubmitted a receipt for "
                + escHtml(period) + ".";

        StringBuilder rows = new StringBuilder();
        rows.append("<table cellpadding='0' cellspacing='0' style='width:100%;border-collapse:collapse;'>");
        rows.append(detailRow("Staff", claimantName));
        rows.append(detailRow("Claim Period", period));
        rows.append("</table>");

        return header("Claim Receipt Resubmitted")
                + greeting("Hi " + approverName)
                + paragraph(intro)
                + detailCard(rows.toString())
                + paragraph("Please log in to the HR portal to review this receipt.")
                + footer();
    }

    /**
     * Builds the email sent to the claimant when their claim becomes wholly approved.
     *
     * @param claimantName who the email greets
     * @param period       the claim period, e.g. "JULY-2026"
     * @param amount       formatted total amount, e.g. "SGD 128.00"
     */
    public static String buildApprovedEmail(String claimantName, String period, String amount) {
        String intro = "Your claim for <strong>" + escHtml(period) + "</strong> of amount "
                + escHtml(amount) + " has been <strong style='color:" + COLOR_APPROVED + ";'>approved</strong>.";

        StringBuilder rows = new StringBuilder();
        rows.append("<table cellpadding='0' cellspacing='0' style='width:100%;border-collapse:collapse;'>");
        rows.append(detailRow("Claim Period", period));
        rows.append(detailRow("Amount", amount));
        rows.append("</table>");

        return header("Staff Claim Approved")
                + greeting("Hi " + claimantName)
                + paragraph(intro)
                + detailCard(rows.toString())
                + paragraph("No further action is required. You can view the details in the HR portal.")
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
