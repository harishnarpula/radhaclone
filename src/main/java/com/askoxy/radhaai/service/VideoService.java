package com.askoxy.radhaai.service;

import com.askoxy.radhaai.dto.*;
import com.askoxy.radhaai.entity.VideoContent;
import com.askoxy.radhaai.enums.ContentStatus;
import com.askoxy.radhaai.repository.VideoContentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.util.*;
import com.askoxy.radhaai.dto.PlatformContent;
import com.askoxy.radhaai.repository.ContentItemRepository;
import com.askoxy.radhaai.entity.ContentItem;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoService {

    private final BlogService            blogService;
    private final AIService              aiService;
    private final GeminiVideoService     geminiVideoService;
    private final IngestionService       ingestionService;
    private final SocialMediaService     socialMediaService;
    private final VideoContentRepository videoContentRepository;
    private final ObjectMapper           objectMapper;
    private final ContentItemRepository  contentItemRepository;

    private static final String UPLOAD_DIR = "uploads/videos/";

    public VideoContent findById(String entityId) {
        return find(entityId);
    }

    @Transactional
    public VideoContent submit(MultipartFile videoFile) throws Exception {

        String storagePath = saveFile(videoFile);
        VideoAnalysisResult analysis = geminiVideoService.analyzeVideo(videoFile);

        VideoContent content = VideoContent.builder()
                .videoId(UUID.randomUUID().toString())
                .originalFileName(videoFile.getOriginalFilename())
                .storagePath(storagePath)
                .fileSizeBytes(videoFile.getSize())
                .audioTranscript(analysis.getAudioTranscript())
                .visualContent(analysis.getVisualContent())
                .reasoningNotes(analysis.getReasoningNotes())
                .reasonedContent(analysis.getReasonedContent())
                .title(analysis.getTitle())
                .summary(analysis.getSummary())
                .intro(analysis.getIntro())
                .body(analysis.getBody())
                .closing(analysis.getClosing())
                .approvedContent(
                        analysis.getReasonedContent() != null
                                ? analysis.getReasonedContent()
                                : analysis.getDraftPost())
                .status(ContentStatus.PENDING)
                .addedToClone(false)
                .blogPublished(false)
                .socialPosted(false)
                .build();

        videoContentRepository.save(content);
        log.info("Video saved: videoId={}", content.getVideoId());
        return content;
    }

    @Transactional
    public Object addToClone(CloneApprovalRequest req) {

        if (!Boolean.TRUE.equals(req.getConfirmed()))
            throw new RuntimeException("Confirmation required.");

        if ("CONTENT".equalsIgnoreCase(req.getEntityType())) {

            ContentItem item = contentItemRepository.findByContentId(req.getEntityId())
                    .orElseThrow(() -> new RuntimeException("Content not found: " + req.getEntityId()));

            String text = (req.getEditedContent() != null && !req.getEditedContent().isBlank())
                    ? req.getEditedContent() : item.getGeneratedContent();

            ingestionService.storeContent(item.getContentId(), text, "CONTENT");
            item.setEditedContent(text);
            item.setStatus(ContentStatus.APPROVED);
            return contentItemRepository.save(item);

        } else {

            VideoContent content = videoContentRepository.findByVideoId(req.getEntityId())
                    .orElseThrow(() -> new RuntimeException("Video not found: " + req.getEntityId()));

            String text = (req.getEditedContent() != null && !req.getEditedContent().isBlank())
                    ? req.getEditedContent() : content.getApprovedContent();

            String vectorStoreId = ingestionService.storeContent(content.getVideoId(), text, "VIDEO_CONTENT");
            content.setApprovedContent(text);
            content.setVectorStoreId(vectorStoreId);
            content.setAddedToClone(true);
            updateStatus(content);
            return videoContentRepository.save(content);
        }
    }

    @Transactional
    public SocialFormatResult formatForSocial(SocialFormatRequest req) {

        VideoContent content = find(req.getEntityId());

        String textToFormat = (req.getEditedContent() != null && !req.getEditedContent().isBlank())
                ? req.getEditedContent()
                : content.getApprovedContent();

        Map<String, PlatformContent> formatted =
                socialMediaService.formatForPlatforms(
                        textToFormat,
                        req.getPlatforms(),
                        content.getThumbnailUrl(),
                        content.getStoragePath()
                );

        try {
            content.setSocialFormats(objectMapper.writeValueAsString(formatted));
        } catch (Exception ignored) {}

        videoContentRepository.save(content);

        return SocialFormatResult.builder()
                .entityId(content.getVideoId())
                .formats(formatted)
                .build();
    }

    @Transactional
    public SocialPostResult postToSocial(SocialPostRequest req) {

        VideoContent content = find(req.getEntityId());
        Map<String, String> results = socialMediaService.postToAllPlatforms(req.getApprovedBodies());

        try { content.setSocialPostResults(objectMapper.writeValueAsString(results)); } catch (Exception ignored) {}
        content.setSocialPosted(true);
        updateStatus(content);
        videoContentRepository.save(content);

        return SocialPostResult.builder()
                .entityId(req.getEntityId())
                .results(results)
                .build();
    }

    @Transactional
    public BlogFormatResult formatForBlog(String entityId, String entityType, boolean generateImage) {

        String contentText;
        String imageUrl;
        String storagePath = null;
        String blogTitle   = "";
        String blogBody    = "";
        String blogClosing = "";
        String blogSummary = "";

        if ("CONTENT".equalsIgnoreCase(entityType)) {
            ContentItem item = contentItemRepository.findByContentId(entityId)
                    .orElseThrow(() -> new RuntimeException("Content not found: " + entityId));
            contentText = item.getEditedContent() != null ? item.getEditedContent() : item.getGeneratedContent();
            imageUrl    = item.getImageUrl();

        } else {
            VideoContent content = find(entityId);
            contentText = content.getApprovedContent() != null ? content.getApprovedContent() : content.getReasonedContent();
            imageUrl    = content.getThumbnailUrl();
            storagePath = content.getStoragePath();
            blogTitle   = content.getTitle()   != null ? content.getTitle()   : "";
            blogBody    = content.getBody()     != null ? content.getBody()    : "";
            blogClosing = content.getClosing()  != null ? content.getClosing() : "";
            blogSummary = content.getSummary()  != null ? content.getSummary() : "";

            if (generateImage) {
                generateImageForContent(entityId, contentText, content);
                content     = find(entityId);
                imageUrl    = content.getThumbnailUrl();
                storagePath = content.getStoragePath();
            }
        }

        String fullContext = "APPROVED CONTENT:\n" + contentText
                + (blogTitle.isBlank()   ? "" : "\n\nTITLE: "   + blogTitle)
                + (blogSummary.isBlank() ? "" : "\n\nSUMMARY: " + blogSummary)
                + (blogBody.isBlank()    ? "" : "\n\nBODY: "    + blogBody)
                + (blogClosing.isBlank() ? "" : "\n\nCLOSING: " + blogClosing);

        String videoFileUrl = generateImage ? null : resolveVideoFileUrl(storagePath);

        String rawJson = aiService.chat("""
                You are an expert blog writer for AskOxy. Return ONLY valid JSON. No markdown outside the JSON values.

                BLOG WRITING RULES:
                - Use the SOURCE CONTENT as input — extract key ideas and facts
                - Write FRESH, ORIGINAL content — do NOT copy source text word-for-word
                - Write naturally in first person as Radha Krishna Thatavarthi
                                                                  - Only use phrases like:
                                                                    "As Radha Sir"
                                                                    "As CEO"
                                                                    "As Founder"
                                                                    if the source content explicitly mentions them
                                                                  - Otherwise speak directly and naturally according to the content
                                                                  - Do NOT overuse identity references
                - SEO-friendly title (6-12 words, include main keyword)
                - Start with an engaging introduction paragraph
                - Write 2-3 main sections with bold headings using **Heading**
                - Use bullet points where listing features or benefits
                - End with a conclusion paragraph and clear call to action
                - 600-1000 words
                - Professional but warm, accessible tone
                - NO hashtags anywhere
                - Add META at the very end: META: [SEO description under 160 chars]
                - Zero spelling or grammar mistakes

                Source content:
                %s

                Return ONLY this JSON:
                {
                  "title": "<compelling blog title — 6-12 words, SEO-friendly>",
                  "description": "<full blog post with **bold headings**, bullet points, conclusion, META line at end>",
                  "socialMediaCaptions": "<2-3 sentences to share this blog. No hashtags.>"
                }
                """.formatted(fullContext));

        BlogFormatResult result;
        try {
            String clean = rawJson.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();
            @SuppressWarnings("unchecked")
            Map<String, String> parsed = objectMapper.readValue(clean, Map.class);
            result = BlogFormatResult.builder()
                    .entityId(entityId)
                    .entityType(entityType)
                    .title(parsed.getOrDefault("title", "New Post by Radha"))
                    .description(parsed.getOrDefault("description", contentText))
                    .socialMediaCaptions(parsed.getOrDefault("socialMediaCaptions", ""))
                    .addedBy("Radha")
                    .videoUrl(storagePath)
                    .videoFileUrl(videoFileUrl)
                    .imageUrl(imageUrl)
                    .status(imageUrl != null ? "IMAGE_READY" : "DRAFT")
                    .build();
        } catch (Exception ex) {
            log.error("Blog AI parse failed for entityId={}", entityId, ex);
            result = BlogFormatResult.builder()
                    .entityId(entityId)
                    .entityType(entityType)
                    .title("New Post by Radha")
                    .description(contentText)
                    .socialMediaCaptions("")
                    .addedBy("Radha")
                    .videoUrl(storagePath)
                    .videoFileUrl(videoFileUrl)
                    .imageUrl(imageUrl)
                    .status(imageUrl != null ? "IMAGE_READY" : "DRAFT")
                    .build();
        }

        if ("CONTENT".equalsIgnoreCase(entityType)) {
            ContentItem item = contentItemRepository.findByContentId(entityId).orElseThrow();
            try { item.setBlogFormat(objectMapper.writeValueAsString(result)); } catch (Exception ignored) {}
            contentItemRepository.save(item);
        } else {
            VideoContent content = find(entityId);
            try { content.setBlogFormat(objectMapper.writeValueAsString(result)); } catch (Exception ignored) {}
            videoContentRepository.save(content);
        }

        return result;
    }

    @Transactional
    public BlogFormatResult generateBlogImage(String entityId, String entityType) {

        String contentText;
        String imageUrl;
        String storagePath = null;

        if ("CONTENT".equalsIgnoreCase(entityType)) {

            ContentItem item = contentItemRepository.findByContentId(entityId)
                    .orElseThrow(() ->
                            new RuntimeException("Content not found: " + entityId));

            contentText = item.getEditedContent() != null
                    ? item.getEditedContent()
                    : item.getGeneratedContent();

            try {
                String imagePrompt = aiService.buildImagePrompt(contentText, "BLOG");
                imageUrl = aiService.generateImage(imagePrompt);

                item.setImageUrl(imageUrl);

                BlogFormatResult result = BlogFormatResult.builder()
                        .entityId(entityId)
                        .entityType("CONTENT")
                        .imageUrl(imageUrl)
                        .addedBy("Radha")
                        .status("IMAGE_READY")
                        .build();

                try {
                    item.setBlogFormat(objectMapper.writeValueAsString(result));
                } catch (Exception ignored) {}

                contentItemRepository.save(item);

                return result;

            } catch (Exception ex) {
                throw new RuntimeException("Image generation failed");
            }

        } else {

            VideoContent content = find(entityId);

            contentText = content.getApprovedContent() != null
                    ? content.getApprovedContent()
                    : content.getReasonedContent();

            imageUrl = generateImageForContent(entityId, contentText, content);

            storagePath = content.getStoragePath();

            BlogFormatResult result = BlogFormatResult.builder()
                    .entityId(entityId)
                    .entityType("VIDEO")
                    .imageUrl(imageUrl)
                    .videoUrl(storagePath)
                    .videoFileUrl(resolveVideoFileUrl(storagePath))
                    .addedBy("Radha")
                    .status("IMAGE_READY")
                    .build();

            try {
                content.setBlogFormat(objectMapper.writeValueAsString(result));
            } catch (Exception ignored) {}

            videoContentRepository.save(content);

            return result;
        }
    }

    @Transactional
    public BlogFormatResult publishBlog(BlogFormatResult blogResult) {

        String imageUrlToUse = blogResult.getImageUrl() != null ? blogResult.getImageUrl() : "";

        String videoFileUrlToUse;
        if ("CONTENT".equalsIgnoreCase(blogResult.getEntityType())) {
            videoFileUrlToUse = blogResult.getVideoFileUrl() != null ? blogResult.getVideoFileUrl() : "";
        } else {
            VideoContent content = find(blogResult.getEntityId());
            videoFileUrlToUse =
                    (blogResult.getVideoFileUrl() != null && !blogResult.getVideoFileUrl().isBlank())
                            ? blogResult.getVideoFileUrl()
                            : resolveVideoFileUrl(content.getStoragePath());
        }

        String result = blogService.post(
                blogResult.getTitle(),
                blogResult.getDescription(),
                imageUrlToUse,
                videoFileUrlToUse
        );

        if (result != null && result.startsWith("POSTED")) {
            blogResult.setStatus("PUBLISHED");
            blogResult.setBlogPostId("POSTED SUCCESSFULLY");
        } else {
            blogResult.setStatus("FAILED: " + result);
        }

        if ("CONTENT".equalsIgnoreCase(blogResult.getEntityType())) {
            ContentItem item = contentItemRepository.findByContentId(blogResult.getEntityId())
                    .orElseThrow(() -> new RuntimeException("Content not found: " + blogResult.getEntityId()));
            item.setBlogPublished(true);
            try { item.setBlogFormat(objectMapper.writeValueAsString(blogResult)); } catch (Exception ignored) {}
            contentItemRepository.save(item);

        } else {
            VideoContent content = find(blogResult.getEntityId());
            content.setBlogPostId(blogResult.getBlogPostId());
            content.setBlogPublished(true);
            updateStatus(content);
            try { content.setBlogFormat(objectMapper.writeValueAsString(blogResult)); } catch (Exception ignored) {}
            videoContentRepository.save(content);
        }

        return blogResult;
    }

    public List<VideoContent> getAll() { return videoContentRepository.findAll(); }

    private String generateImageForContent(String id, String contentText, VideoContent content) {
        try {
            log.info("Generating blog image for id={}", id);
            String imagePrompt = aiService.buildImagePrompt(contentText, "BLOG");
            String imageUrl    = aiService.generateImage(imagePrompt);
            content.setThumbnailUrl(imageUrl);
            videoContentRepository.save(content);
            log.info("Blog image generated for id={} url={}", id, imageUrl);
            return imageUrl;
        } catch (Exception ex) {
            log.error("Blog image generation failed for id={}", id, ex);
            return null;
        }
    }

    private static final String VIDEO_SERVE_BASE = "https://meta.oxyloans.com/radha-ai/files/";

    private String resolveVideoFileUrl(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) return null;
        if (storagePath.startsWith("http://") || storagePath.startsWith("https://"))
            return storagePath;
        String normalized = storagePath.replace("\\", "/");
        if (normalized.startsWith("uploads/"))
            normalized = normalized.substring("uploads/".length());
        return VIDEO_SERVE_BASE + normalized;
    }

    private VideoContent find(String id) {
        return videoContentRepository.findByVideoId(id)
                .orElseThrow(() -> new RuntimeException("Video not found: " + id));
    }

    private String saveFile(MultipartFile file) throws Exception {
        Path dir = Paths.get(UPLOAD_DIR);
        if (!Files.exists(dir)) Files.createDirectories(dir);
        String ext = file.getOriginalFilename() != null && file.getOriginalFilename().contains(".")
                ? file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf(".")) : ".mp4";
        Path target = dir.resolve(UUID.randomUUID() + ext);
        Files.write(target, file.getBytes());
        return target.toString();
    }

    private void updateStatus(VideoContent content) {
        if (content.getAddedToClone()
                || content.getBlogPublished()
                || content.getSocialPosted()) {
            content.setStatus(ContentStatus.APPROVED);
        }
    }
}
