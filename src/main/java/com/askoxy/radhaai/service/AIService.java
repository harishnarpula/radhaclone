package com.askoxy.radhaai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIService {

    private final ChatClient chatClient;

    @Value("${spring.ai.openai.api-key}")
    private String openAiApiKey;

    // ─────────────────────────────────────────────────────────────────────────
    // TEXT CHAT
    // ─────────────────────────────────────────────────────────────────────────

    public String chat(String systemPrompt, String userPrompt) {

        log.info("Sending chat request to AI");

        try {

            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();

            log.info(
                    "Chat completion successful: {} chars",
                    response != null ? response.length() : 0);

            return response != null ? response : "";

        } catch (Exception ex) {

            log.error("Chat completion failed", ex);

            throw new RuntimeException(
                    "AI chat generation failed: " + ex.getMessage(),
                    ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WHISPER AUDIO TRANSCRIPTION
    // ─────────────────────────────────────────────────────────────────────────

    public String transcribe(MultipartFile audioFile) {

        log.info(
                "Transcribing audio: name={} size={}KB",
                audioFile.getOriginalFilename(),
                audioFile.getSize() / 1024);

        try {

            byte[] bytes = audioFile.getBytes();

            String fileName =
                    audioFile.getOriginalFilename() != null
                            ? audioFile.getOriginalFilename()
                            : "audio.mp3";

            MultiValueMap<String, Object> body =
                    new LinkedMultiValueMap<>();

            body.add("model", "whisper-1");

            body.add("file", new ByteArrayResource(bytes) {

                @Override
                public String getFilename() {
                    return fileName;
                }
            });

            HttpHeaders headers = new HttpHeaders();

            headers.setContentType(
                    MediaType.MULTIPART_FORM_DATA);

            headers.setBearerAuth(openAiApiKey);

            RestTemplate restTemplate = new RestTemplate();

            ResponseEntity<Map> response =
                    restTemplate.exchange(
                            "https://api.openai.com/v1/audio/transcriptions",
                            HttpMethod.POST,
                            new HttpEntity<>(body, headers),
                            Map.class
                    );

            String text =
                    (String) response.getBody().get("text");

            log.info(
                    "Transcription complete: {} chars",
                    text != null ? text.length() : 0);

            return text != null ? text : "";

        } catch (Exception ex) {

            log.error("Whisper transcription failed", ex);

            throw new RuntimeException(
                    "Voice transcription failed: "
                            + ex.getMessage(),
                    ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IMAGE UNDERSTANDING / OCR / VISION
    // ─────────────────────────────────────────────────────────────────────────

    public String extractImageText(MultipartFile imageFile) {

        log.info(
                "Processing image: name={} size={}KB",
                imageFile.getOriginalFilename(),
                imageFile.getSize() / 1024);

        try {

            byte[] bytes = imageFile.getBytes();

            String base64 =
                    java.util.Base64.getEncoder()
                            .encodeToString(bytes);

            String prompt = """
                    Analyze this image carefully.

                    Extract:
                    - visible text
                    - banners
                    - posters
                    - screenshots
                    - marketing messages
                    - business information
                    - important context

                    Return only clean readable text.
                    """;

            Map<String, Object> payload = Map.of(
                    "model", "gpt-4o-mini",
                    "messages", List.of(
                            Map.of(
                                    "role", "user",
                                    "content", List.of(
                                            Map.of(
                                                    "type", "text",
                                                    "text", prompt
                                            ),
                                            Map.of(
                                                    "type", "image_url",
                                                    "image_url", Map.of(
                                                            "url",
                                                            "data:image/jpeg;base64," + base64
                                                    )
                                            )
                                    )
                            )
                    ),
                    "max_tokens", 1000
            );

            HttpHeaders headers = new HttpHeaders();

            headers.setContentType(MediaType.APPLICATION_JSON);

            headers.setBearerAuth(openAiApiKey);

            RestTemplate restTemplate = new RestTemplate();

            ResponseEntity<Map> response =
                    restTemplate.exchange(
                            "https://api.openai.com/v1/chat/completions",
                            HttpMethod.POST,
                            new HttpEntity<>(payload, headers),
                            Map.class
                    );

            List<Map<String, Object>> choices =
                    (List<Map<String, Object>>)
                            response.getBody().get("choices");

            if (choices == null || choices.isEmpty()) {
                return "";
            }

            Map<String, Object> message =
                    (Map<String, Object>)
                            choices.get(0).get("message");

            String text =
                    (String) message.get("content");

            log.info(
                    "Image extraction complete: {} chars",
                    text != null ? text.length() : 0);

            return text != null ? text : "";

        } catch (Exception ex) {

            log.error("Image extraction failed", ex);

            return "";
        }
    }
}