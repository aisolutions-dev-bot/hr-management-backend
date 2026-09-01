package com.aisolutions.hrmanagement.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * OpenAI REST Client — supports text-only and vision (image) messages.
 *
 * For vision, the "content" field must be a JSON array:
 *   [ { "type": "text", "text": "..." },
 *     { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,..." } } ]
 *
 * We use two separate Message subclasses serialised via @JsonInclude to keep
 * null fields out of the JSON, which is cleaner than @JsonProperty tricks.
 */
@RegisterRestClient(configKey = "openai-api")
@Path("/v1/chat/completions")
@ClientHeaderParam(name = "Authorization", value = "Bearer ${openai.api.key}")
public interface OpenAIClient {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Uni<OpenAIResponse> chat(OpenAIRequest request);

    // ── Request ──────────────────────────────────────────────────────────

    @JsonInclude(JsonInclude.Include.NON_NULL)
    class OpenAIRequest {
        public String model = "gpt-4o-mini";
        public List<Object> messages;   // Object to hold either TextMessage or VisionMessage
        public double temperature = 0.1;
        public int max_tokens = 512;

        public OpenAIRequest() {}
        public OpenAIRequest(List<Object> messages) { this.messages = messages; }
    }

    /** Plain text message — content is a String */
    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class TextMessage {
        public String role;
        public String content;

        public TextMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /** Vision message — content is a List of ContentPart */
    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class VisionMessage {
        public String role;
        public List<ContentPart> content;

        public VisionMessage(String role, List<ContentPart> content) {
            this.role = role;
            this.content = content;
        }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class ContentPart {
        public String type;
        public String text;
        public ImageUrl image_url;

        /** Factory: text part */
        public static ContentPart text(String text) {
            ContentPart p = new ContentPart();
            p.type = "text";
            p.text = text;
            return p;
        }

        /** Factory: image_url part */
        public static ContentPart image(String base64, String mimeType) {
            ContentPart p = new ContentPart();
            p.type = "image_url";
            p.image_url = new ImageUrl("data:" + mimeType + ";base64," + base64);
            return p;
        }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    class ImageUrl {
        public String url;
        public ImageUrl() {}
        public ImageUrl(String url) { this.url = url; }
    }

    // ── Response ─────────────────────────────────────────────────────────

    class OpenAIResponse {
        public String id;
        public List<Choice> choices;

        public String getContent() {
            if (choices != null && !choices.isEmpty() && choices.get(0).message != null) {
                return choices.get(0).message.content;
            }
            return null;
        }

        public static class Choice {
            public int index;
            public Message message;
            public String finish_reason;

            public static class Message {
                public String role;
                public String content;
            }
        }
    }
}
