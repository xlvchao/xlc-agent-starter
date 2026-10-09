package com.xlc.ai.domain.agent.service.install.matter.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.springai.MessageConverter;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 补丁
 *
 * @author xlvchao
 */
public class MyMessageConverter extends MessageConverter {

    public MyMessageConverter(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public Prompt toLlmPrompt(LlmRequest llmRequest) {

        List<Media> mediaList = new ArrayList<>();
        for (Content content : llmRequest.contents()) {
            for (Part part : content.parts().orElse(List.of())) {
                if (part.inlineData().isPresent()) {
                    // Handle inline media data (images, audio, video, etc.)
                    com.google.genai.types.Blob blob = part.inlineData().get();
                    if (blob.mimeType().isPresent() && blob.data().isPresent()) {
                        try {
                            MimeType mimeType = MimeType.valueOf(blob.mimeType().get());
                            // Create Media object from inline data using ByteArrayResource
                            org.springframework.core.io.ByteArrayResource resource =
                                    new org.springframework.core.io.ByteArrayResource(blob.data().get());
                            mediaList.add(new Media(mimeType, resource));
                        } catch (Exception e) {
                            // Log warning but continue processing other parts
                            // In production, consider proper logging framework
                            System.err.println(
                                    "Warning: Failed to parse media mime type: " + blob.mimeType().get());
                        }
                    }
                } else if (part.fileData().isPresent()) {
                    // Handle file-based media (URI references)
                    com.google.genai.types.FileData fileData = part.fileData().get();
                    if (fileData.mimeType().isPresent() && fileData.fileUri().isPresent()) {
                        try {
                            MimeType mimeType = MimeType.valueOf(fileData.mimeType().get());
                            // Create Media object from file URI
                            URI uri = URI.create(fileData.fileUri().get());
                            mediaList.add(new Media(mimeType, uri));
                        } catch (Exception e) {
                            System.err.println(
                                    "Warning: Failed to parse media mime type: " + fileData.mimeType().get());
                        }
                    }
                }
            }
        }

        Prompt llmPrompt = super.toLlmPrompt(llmRequest);
        llmPrompt.getUserMessage().getMedia().addAll(mediaList);

        return llmPrompt;
    }

}
