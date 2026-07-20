package com.aisolutions.hrmanagement.service.ocr;

import com.aisolutions.hrmanagement.client.OpenAIClient;
import com.aisolutions.hrmanagement.client.OpenAIClient.*;
import com.aisolutions.hrmanagement.dto.OcrReceiptResultDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Extracts receipt data from an image using OpenAI GPT-4o Vision.
 *
 * Replaces the previous Tesseract.js browser-based OCR approach.
 * GPT-4o vision understands receipt layout semantically — no keyword rules
 * or merchant training data required.
 */
@ApplicationScoped
public class ReceiptOcrService {

    private static final Logger LOG = Logger.getLogger(ReceiptOcrService.class);

    @Inject
    @RestClient
    OpenAIClient openAIClient;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT =
        "You are a receipt data extraction assistant. " +
        "Extract structured data from receipt images with high precision. " +
        "Always respond with valid JSON only — no markdown, no explanation.";

    private static final String USER_PROMPT =
        "Extract the following fields from this receipt image:\n" +
        "- merchantName: the business/restaurant/store name\n" +
        "- receiptNumber: receipt, invoice, order, or transaction number\n" +
        "- receiptDate: date of the receipt in yyyy-MM-dd format\n" +
        "- receiptAmount: the final total amount paid (numeric, no currency symbol)\n" +
        "- currency: the ISO code of the receipt's currency (e.g. MYR, SGD, USD). " +
        "Use the symbol/code and any location or language cues. If it shows only a " +
        "bare \"$\" or you cannot tell, use null — do not guess.\n" +
        "\n" +
        "Respond ONLY with this JSON structure (use null for fields not found):\n" +
        "{\n" +
        "  \"merchantName\": \"...\",\n" +
        "  \"receiptNumber\": \"...\",\n" +
        "  \"receiptDate\": \"...\",\n" +
        "  \"receiptAmount\": 0.00,\n" +
        "  \"currency\": \"...\"\n" +
        "}";

    /**
     * Extracts receipt fields from a raw image byte array.
     *
     * @param imageBytes  raw bytes of the receipt image (JPG, PNG, etc.)
     * @param mimeType    content type e.g. "image/jpeg", "image/png"
     * @return            extracted receipt fields
     */
    public Uni<OcrReceiptResultDTO> extractFromImage(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            LOG.warn("[ReceiptOcr] Empty image bytes received");
            OcrReceiptResultDTO err = new OcrReceiptResultDTO();
            err.setSuccess(false);
            err.setErrorMessage("Image is empty or missing");
            return Uni.createFrom().item(err);
        }

        // Convert image to base64
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String mime = (mimeType != null && !mimeType.isBlank()) ? mimeType : "image/jpeg";

        // System message: text only
        var systemMsg = new TextMessage("system", SYSTEM_PROMPT);

        // User message: vision — text prompt + base64 image
        var userMsg = new VisionMessage("user", List.of(
            ContentPart.text(USER_PROMPT),
            ContentPart.image(base64, mime)
        ));

        OpenAIRequest request = new OpenAIRequest(List.of(systemMsg, userMsg));
        request.model = "gpt-4o-mini";
        request.temperature = 0.1;
        request.max_tokens = 512;

        LOG.info("[ReceiptOcr] Sending image to OpenAI (" + imageBytes.length + " bytes, " + mime + ")");

        return openAIClient.chat(request)
            .map(response -> {
                String content = response.getContent();
                LOG.info("[ReceiptOcr] OpenAI response: " + content);
                return parseResponse(content, null);
            })
            .onFailure().recoverWithItem(err -> {
                LOG.error("[ReceiptOcr] OpenAI call failed: " + err.getMessage(), err);
                OcrReceiptResultDTO result = new OcrReceiptResultDTO();
                result.setSuccess(false);
                result.setErrorMessage("AI extraction failed: " + err.getMessage());
                return result;
            });
    }

    // ─────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private OcrReceiptResultDTO parseResponse(String json, String rawText) {
        OcrReceiptResultDTO result = new OcrReceiptResultDTO();
        result.setRawText(rawText);

        if (json == null || json.isBlank()) {
            result.setSuccess(false);
            result.setErrorMessage("Empty response from AI");
            return result;
        }

        try {
            // Strip markdown code fences if present
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("```[a-z]*\\n?", "").replace("```", "").trim();
            }

            Map<String, Object> parsed = mapper.readValue(cleaned, Map.class);

            result.setMerchantName(getString(parsed, "merchantName"));
            result.setReceiptNumber(getString(parsed, "receiptNumber"));
            result.setReceiptDate(getString(parsed, "receiptDate"));

            Object amtRaw = parsed.get("receiptAmount");
            if (amtRaw != null) {
                try {
                    result.setReceiptAmount(new java.math.BigDecimal(amtRaw.toString()));
                } catch (Exception e) {
                    LOG.warn("[ReceiptOcr] Could not parse amount: " + amtRaw);
                }
            }

            result.setCurrency(getString(parsed, "currency"));

            result.setSuccess(true);
        } catch (Exception e) {
            LOG.error("[ReceiptOcr] Failed to parse JSON response: " + e.getMessage());
            result.setSuccess(false);
            result.setErrorMessage("Failed to parse AI response: " + e.getMessage());
        }

        return result;
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null || "null".equals(val.toString())) return null;
        String s = val.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
