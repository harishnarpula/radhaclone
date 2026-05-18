package com.askoxy.radhaai.service;

import com.askoxy.radhaai.dto.*;
import com.askoxy.radhaai.entity.ContentItem;
import com.askoxy.radhaai.enums.ContentStatus;
import com.askoxy.radhaai.enums.PlatformType;
import com.askoxy.radhaai.repository.ContentItemRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentService {

    private final AIService aiService;
    private final DocumentService documentService;
    private final IngestionService ingestionService;
    private final ContentItemRepository contentItemRepository;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    @Value("${radha.n8n.webhook-base-url:http://localhost:5678/webhook}")
    private String n8nWebhookBaseUrl;

    @Value("${radha.n8n.enabled:false}")
    private boolean n8nEnabled;

    // ─────────────────────────────────────────────────────────────────────────
    // SUBMIT
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public ContentItem submit(ContentRequest req) throws Exception {

        log.info("Content pipeline started — platform={}", req.getPlatform());

        // ── Collect inputs cleanly — no labels ──────────────────────────────

        // Text
        String textInput = "";
        if (req.getRawInstruction() != null && !req.getRawInstruction().isBlank())
            textInput = normalizeText(req.getRawInstruction());

        // Voice → transcribe
        String voiceInput = "";
        if (req.getVoiceFile() != null && !req.getVoiceFile().isEmpty()) {
            log.info("Transcribing voice file via Whisper");
            voiceInput = normalizeText(aiService.transcribe(req.getVoiceFile()));
            log.info("VOICE TRANSCRIPT:\n{}", voiceInput);
        }

        // File → AI smart summary (NOT raw dump)
        String fileInput = "";
        if (req.getAttachment() != null && !req.getAttachment().isEmpty()) {
            fileInput = smartSummarizeFile(req.getAttachment());
            log.info("FILE SMART SUMMARY:\n{}", fileInput);
        }

        if (textInput.isEmpty() && voiceInput.isEmpty() && fileInput.isEmpty())
            throw new IllegalArgumentException(
                    "No input provided — please send text, voice, image, audio, or attachment.");

        // Combine cleanly — no labels
        StringBuilder combined = new StringBuilder();
        if (!textInput.isEmpty())  combined.append(textInput).append("\n\n");
        if (!voiceInput.isEmpty()) combined.append(voiceInput).append("\n\n");
        if (!fileInput.isEmpty())  combined.append(fileInput);

        String finalInstruction = combined.toString().trim();
        log.info("FINAL COMBINED INPUT:\n{}", finalInstruction);

        // For UI/DB — store only CEO's original clean text, not file dump
        String rawInstructionForStorage = !textInput.isEmpty() ? textInput
                : !voiceInput.isEmpty() ? voiceInput : "(file input only)";

        String platformLabel = resolvePlatformLabel(
                req.getPlatform(),
                req.getCustomPlatformName());

        // STEP 1 — REASONING + TOPIC CLASSIFICATION
        String cleanedIdeaJson = reasonInstruction(finalInstruction, platformLabel);
        log.info("CLEANED IDEA JSON:\n{}", cleanedIdeaJson);

        boolean sameTopicGroup = extractSameTopicGroup(cleanedIdeaJson);
        log.info("sameTopicGroup={} platform={}", sameTopicGroup, platformLabel);

        // STEP 2 — PLATFORM-FILTERED RAG
        String ragContext = retrieve(cleanedIdeaJson, 5, platformLabel);
        log.info("RAG CONTEXT:\n{}", ragContext);

        // STEP 3 — GENERATION (branched by topic classification)
        String generatedJson = sameTopicGroup
                ? generateGroupedContent(cleanedIdeaJson, platformLabel, ragContext)
                : generateSeparateContent(cleanedIdeaJson, platformLabel, ragContext);

        // STEP 4 — SAVE
        ContentItem item = ContentItem.builder()
                .contentId(UUID.randomUUID().toString())
                .rawInstruction(rawInstructionForStorage)
                .extractedInputs(finalInstruction)
                .platform(req.getPlatform())
                .customPlatformName(req.getCustomPlatformName())
                .generatedContent(generatedJson)
                .isGrouped(sameTopicGroup)
                .status(ContentStatus.PENDING)
                .build();

        contentItemRepository.save(item);
        log.info("Saved to approval queue: contentId={}", item.getContentId());
        return item;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // APPROVAL
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public ContentItem processApproval(ContentApprovalRequest req)
            throws JsonProcessingException {

        ContentItem item = findByContentId(req.getContentId());

        if (Boolean.TRUE.equals(req.getApproved())) {

            if (req.getEditedContent() != null &&
                    !req.getEditedContent().isBlank()) {
                item.setEditedContent(req.getEditedContent());
            }

            if (req.getFeedback() != null) {
                item.setAdminFeedback(req.getFeedback());
            }

            String contentToStore =
                    (req.getEditedContent() != null &&
                            !req.getEditedContent().isBlank())
                            ? req.getEditedContent()
                            : item.getGeneratedContent();

            String platform =
                    item.getPlatform() != null
                            ? item.getPlatform().name()
                            : "GENERAL";

            log.info("Storing approved content into Qdrant: contentId={} platform={}",
                    item.getContentId(), platform);

            ingestionService.storeContent(
                    item.getContentId(),
                    contentToStore,
                    platform);

            item.setStatus(ContentStatus.APPROVED);
            log.info("Content APPROVED: contentId={}", item.getContentId());

        } else {

            item.setStatus(ContentStatus.REJECTED);
            item.setAdminFeedback(req.getFeedback());
            log.info("Content REJECTED: contentId={}", item.getContentId());
        }

        return contentItemRepository.save(item);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLISH
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public PublishResult publish(PublishRequest req)
            throws JsonProcessingException {

        ContentItem item = findByContentId(req.getContentId());

        Map<String, String> channelResults = new HashMap<>();

        try {

            ContentResult content = resolveContentForPublish(item);

            if (item.getEditedContent() != null &&
                    !item.getEditedContent().isBlank()) {
                content.setBody(item.getEditedContent());
            }

            for (String channel : req.getChannels()) {

                String result = dispatchChannel(
                        channel.toUpperCase(), item, content);

                channelResults.put(channel.toLowerCase(), result);
                log.info("Channel={} result={}", channel, result);
            }

            item.setStatus(ContentStatus.PUBLISHED);
            item.setPublishResults(objectMapper.writeValueAsString(channelResults));
            item.setSelectedChannels(objectMapper.writeValueAsString(req.getChannels()));
            contentItemRepository.save(item);

            return PublishResult.builder()
                    .contentId(item.getContentId())
                    .success(true)
                    .channelResults(channelResults)
                    .message("Published to " + req.getChannels().size() + " channel(s)")
                    .build();

        } catch (Exception ex) {

            log.error("Publish failed: contentId={}", item.getContentId(), ex);
            item.setStatus(ContentStatus.FAILED);
            contentItemRepository.save(item);

            return PublishResult.builder()
                    .contentId(item.getContentId())
                    .success(false)
                    .channelResults(channelResults)
                    .message("Failed: " + ex.getMessage())
                    .build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QUERIES
    // ─────────────────────────────────────────────────────────────────────────

    public List<ContentItem> getPending() {
        return contentItemRepository.findByStatus(ContentStatus.PENDING);
    }

    public List<ContentItem> getApproved() {
        return contentItemRepository.findByStatus(ContentStatus.APPROVED);
    }

    public List<ContentItem> getByPlatform(PlatformType p) {
        return contentItemRepository.findByPlatform(p);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // REASONING
    // ─────────────────────────────────────────────────────────────────────────

    private static final String REASONING_SYSTEM = """
    You are an expert content strategist for the CEO of AskOxy group.

    Analyze ALL ideas in the input and extract:
    - cleanedIdea: concise summary of everything
    - keyPoints: the main ideas found
    - tone: appropriate tone for the platform
    - facts: factual claims to preserve exactly

    TOPIC GROUPING RULE:
    If ALL ideas belong to the SAME company and are related features,
    updates, or aspects of the SAME business ecosystem → sameTopicGroup = true
    If ideas belong to DIFFERENT companies OR completely unrelated topics → sameTopicGroup = false

    Examples:
      "proximity lending + instant approval + AI risk" all for OXY_LOANS → true
      "OXY_LOANS feature" + "OXY_GOLD update" → false

    STRICT RULES:
    - Never invent anything
    - Do not merge unrelated companies
    - Output ONLY valid JSON, no markdown fences

    Return exactly:
    {
      "cleanedIdea":"...",
      "keyPoints":["..."],
      "tone":"...",
      "facts":["..."],
      "sameTopicGroup": true,
      "topicGroups":[{"groupName":"...","points":["..."]}]
    }
    """;

    private String reasonInstruction(String rawInstruction, String platformLabel) {

        String user = """
            BUSINESS PLATFORM: %s

            CEO RAW INPUT:
            %s
            """.formatted(platformLabel, rawInstruction);

        String response = aiService.chat(REASONING_SYSTEM, user);
        return cleanJson(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GENERATION
    // ─────────────────────────────────────────────────────────────────────────

    private static final String GENERATION_SYSTEM_GROUPED = """
    You are the personal content writer for the CEO of AskOxy group.

    The ideas are all part of the SAME business ecosystem.
    Write ONE unified content piece with MULTIPLE SECTIONS.

    Structure:
    - One strong overall title
    - An opening intro paragraph
    - One section per key idea (heading + paragraph body)
    - A closing paragraph tying everything together
    - Shared hashtags and CTA at the end

    STRICT RULES:
    - CEO intent is the ONLY source of truth
    - Use company KB facts only where directly relevant
    - No hashtags inside body or sections
    - Write professionally and clearly
    - NEVER include labels like "USER INPUT", "ATTACHMENT CONTENT", "FILE SUMMARY" in output

    Return ONLY valid JSON, no markdown:
    {
      "title":"",
      "intro":"",
      "sections":[{"heading":"","body":""}],
      "closing":"",
      "hashtags":"",
      "callToAction":"",
      "isGrouped":true
    }
    """;

    private static final String GENERATION_SYSTEM_SEPARATE = """
    You are the personal content writer for the CEO of AskOxy group.

    The ideas belong to DIFFERENT topics or companies.
    Generate SEPARATE content objects — one per distinct topic or company.

    STRICT RULES:
    - CEO intent is the ONLY source of truth
    - Each content piece must stand alone completely
    - No hashtags inside body
    - Write professionally and clearly
    - NEVER include labels like "USER INPUT", "ATTACHMENT CONTENT", "FILE SUMMARY" in output

    Return ONLY valid JSON array, no markdown:
    [
      {"title":"","body":"","hashtags":"","callToAction":""},
      {"title":"","body":"","hashtags":"","callToAction":""}
    ]
    """;

    private String generateGroupedContent(
            String cleanedIdeaJson,
            String platformLabel,
            String ragContext) {

        String user = """
        BUSINESS PLATFORM: %s

        CEO STRUCTURED IDEA:
        %s

        %s COMPANY KNOWLEDGE BASE (verified facts only):
        %s
        """.formatted(
                platformLabel,
                cleanedIdeaJson,
                platformLabel,
                ragContext.isBlank() ? "No relevant knowledge found." : ragContext);

        String response = aiService.chat(GENERATION_SYSTEM_GROUPED, user);
        log.info("RAW AI RESPONSE (GROUPED):\n{}", response);
        return cleanJson(response);
    }

    private String generateSeparateContent(
            String cleanedIdeaJson,
            String platformLabel,
            String ragContext) {

        String user = """
        BUSINESS PLATFORM: %s

        CEO STRUCTURED IDEA:
        %s

        %s COMPANY KNOWLEDGE BASE (verified facts only):
        %s
        """.formatted(
                platformLabel,
                cleanedIdeaJson,
                platformLabel,
                ragContext.isBlank() ? "No relevant knowledge found." : ragContext);

        String response = aiService.chat(GENERATION_SYSTEM_SEPARATE, user);
        log.info("RAW AI RESPONSE (SEPARATE):\n{}", response);
        return cleanJson(response);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RAG — PLATFORM FILTERED
    // ─────────────────────────────────────────────────────────────────────────

    private String retrieve(String query, int topK, String platformLabel) {

        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(topK);

            if (platformLabel != null && !platformLabel.isBlank()) {
                builder.filterExpression("clientName == '" + platformLabel + "'");
                log.info("RAG filter applied: clientName={}", platformLabel);
            }

            var docs = vectorStore.similaritySearch(builder.build());
            log.info("RAG returned {} chunks for platform={}", docs.size(), platformLabel);

            return String.join(
                    "\n\n",
                    docs.stream()
                            .map(org.springframework.ai.document.Document::getText)
                            .toList());

        } catch (Exception ex) {
            log.warn("RAG retrieval failed for platform={} — continuing without KB", platformLabel, ex);
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLASSIFIER HELPER
    // ─────────────────────────────────────────────────────────────────────────

    private boolean extractSameTopicGroup(String reasoningJson) {
        try {
            return objectMapper.readTree(cleanJson(reasoningJson))
                    .path("sameTopicGroup")
                    .asBoolean(false);
        } catch (Exception ex) {
            log.warn("Could not parse sameTopicGroup — defaulting to false");
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RESOLVE CONTENT FOR PUBLISH
    // ─────────────────────────────────────────────────────────────────────────

    private ContentResult resolveContentForPublish(ContentItem item) {

        String json = item.getGeneratedContent();

        // GROUPED
        if (Boolean.TRUE.equals(item.getIsGrouped())) {
            try {
                ContentResult grouped = objectMapper.readValue(json, ContentResult.class);

                if (grouped.getSections() != null && !grouped.getSections().isEmpty()) {
                    StringBuilder flatBody = new StringBuilder();
                    if (grouped.getIntro() != null)
                        flatBody.append(grouped.getIntro()).append("\n\n");
                    for (ContentSection section : grouped.getSections()) {
                        flatBody.append(section.getHeading()).append("\n");
                        flatBody.append(section.getBody()).append("\n\n");
                    }
                    if (grouped.getClosing() != null)
                        flatBody.append(grouped.getClosing());
                    grouped.setBody(flatBody.toString().trim());
                }
                return grouped;

            } catch (Exception ex) {
                log.warn("Could not parse grouped content, using raw body");
                return ContentResult.builder().body(json).build();
            }
        }

        // SEPARATE ARRAY
        if (json.trim().startsWith("[")) {
            try {
                List<ContentResult> list = objectMapper.readValue(
                        json,
                        objectMapper.getTypeFactory()
                                .constructCollectionType(List.class, ContentResult.class));

                if (!list.isEmpty()) {
                    StringBuilder merged = new StringBuilder();
                    for (ContentResult cr : list) {
                        if (cr.getTitle() != null) merged.append(cr.getTitle()).append("\n");
                        if (cr.getBody() != null)  merged.append(cr.getBody()).append("\n\n");
                    }
                    return ContentResult.builder()
                            .title(list.get(0).getTitle())
                            .body(merged.toString().trim())
                            .hashtags(list.get(0).getHashtags())
                            .callToAction(list.get(0).getCallToAction())
                            .build();
                }
            } catch (Exception ex) {
                log.warn("Could not parse array content, using raw body");
            }
            return ContentResult.builder().body(json).build();
        }

        // FLAT SINGLE
        try {
            return objectMapper.readValue(json, ContentResult.class);
        } catch (Exception ex) {
            log.warn("Could not parse flat content, using raw body");
            return ContentResult.builder().body(json).build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CHANNEL DISPATCH
    // ─────────────────────────────────────────────────────────────────────────

    private String dispatchChannel(
            String channel,
            ContentItem item,
            ContentResult content) {

        if (n8nEnabled) {
            return fireN8nWebhook(channel.toLowerCase(), item, content);
        }

        return switch (channel) {
            case "LINKEDIN"  -> "QUEUED";
            case "INSTAGRAM" -> "QUEUED";
            case "BLOG"      -> "QUEUED";
            case "EMAIL"     -> "QUEUED";
            case "WHATSAPP"  -> "QUEUED";
            default          -> "SKIPPED";
        };
    }

    private String fireN8nWebhook(
            String channel,
            ContentItem item,
            ContentResult content) {

        String url = n8nWebhookBaseUrl + "/" + channel;

        try {
            RestTemplate restTemplate = new RestTemplate();

            Map<String, Object> payload = new HashMap<>();
            payload.put("contentId",          item.getContentId());
            payload.put("channel",            channel.toUpperCase());
            payload.put("platform",           item.getPlatform());
            payload.put("customPlatformName", item.getCustomPlatformName());
            payload.put("title",              content.getTitle());
            payload.put("body",               content.getBody());
            payload.put("hashtags",           content.getHashtags());
            payload.put("callToAction",       content.getCallToAction());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, headers), String.class);

            return response.getStatusCode().is2xxSuccessful()
                    ? "N8N_TRIGGERED"
                    : "N8N_ERROR";

        } catch (Exception ex) {
            return "N8N_FAILED";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private String resolvePlatformLabel(
            PlatformType platform,
            String customPlatformName) {

        if (platform == PlatformType.OTHER &&
                customPlatformName != null &&
                !customPlatformName.isBlank()) {
            return customPlatformName.trim();
        }

        return platform != null ? platform.name() : "ASK_OXY_AI";
    }

    private String normalizeText(String text) {
        if (text == null) return "";
        return text
                .replaceAll("\r\n", "\n")
                .replaceAll("\r", "\n")
                .replaceAll("[ \t]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    // ── Smart File Summarizer — AI reads file, returns key facts only ─────────
    private String smartSummarizeFile(MultipartFile file) {
        String rawText = extractAttachment(file);
        if (rawText.isBlank()) return "";

        String prompt = """
                You are reading a CEO's reference document.
                Extract ONLY the key business facts needed to write great content.
                
                Rules:
                - You decide how many bullet points are needed — not too few, not too many
                - Include: what this product/service is, key features, important numbers, target audience
                - Skip: boilerplate, legal text, repeated info, technical jargon
                - If document is short, keep summary short
                - If document has many facts, cover all important ones
                - No headings. No "the document says". Just clean bullet points.
                """;

        return aiService.chat(prompt, rawText.substring(0, Math.min(rawText.length(), 5000)));
    }

    private String extractAttachment(MultipartFile file) {
        try {
            String name = file.getOriginalFilename();
            if (name == null) return "";
            name = name.toLowerCase();

            String extracted = "";

            // DOCUMENTS
            if (name.endsWith(".pdf")
                    || name.endsWith(".docx")
                    || name.endsWith(".txt")) {
                extracted = documentService.extractText(file);
            }
            // IMAGES
            else if (name.endsWith(".png")
                    || name.endsWith(".jpg")
                    || name.endsWith(".jpeg")
                    || name.endsWith(".webp")) {
                extracted = aiService.extractImageText(file);
            }
            // AUDIO
            else if (name.endsWith(".mp3")
                    || name.endsWith(".wav")
                    || name.endsWith(".m4a")
                    || name.endsWith(".aac")) {
                extracted = aiService.transcribe(file);
            }

            return extracted
                    .replaceAll("\\s+", " ")
                    .replaceAll("[^\\x20-\\x7E\\n]", "")
                    .trim();

        } catch (Exception ex) {
            return "";
        }
    }

    private ContentItem findByContentId(String contentId) {
        return contentItemRepository
                .findByContentId(contentId)
                .orElseThrow(() ->
                        new RuntimeException("Content not found: " + contentId));
    }

    private String cleanJson(String s) {
        return s.replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();
    }
}