package com.askoxy.radhaai.controller;

import com.askoxy.radhaai.dto.*;
import com.askoxy.radhaai.entity.ContentItem;
import com.askoxy.radhaai.enums.PlatformType;
import com.askoxy.radhaai.service.ContentService;
import com.askoxy.radhaai.service.IngestionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Merged controller — handles:
 * 1. Company knowledge ingestion
 * 2. AI content generation
 * 3. Approval workflow
 * 4. Publishing workflow
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ContentController {

    private final ContentService contentService;
    private final IngestionService ingestionService;

    // ─────────────────────────────────────────────────────────────────────────
    // COMPANY KNOWLEDGE UPLOAD
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Upload company knowledge:
     * - PDF
     * - DOCX
     * - PPT
     * - Excel
     * - CSV
     * - TXT
     * - HTML
     * etc.
     *
     * This ONLY stores knowledge into Qdrant.
     * No AI generation happens here.
     */
    @PostMapping(
            value = "/upload/company",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ApiResponse<UploadResponse> uploadCompanyKnowledge(
            @RequestParam("file") MultipartFile file,
            @RequestParam PlatformType platformType,
            @RequestParam(required = false) String description) {

        // LARGE FILE LIMIT
        if (file.getSize() > 200 * 1024 * 1024) {

            throw new RuntimeException(
                    "Company upload too large. Max allowed size is 200MB.");
        }

        return ApiResponse.success(
                ingestionService.upload(
                        file,
                        platformType,
                        description));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTENT SUBMIT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * CEO/admin daily content generation API.
     *
     * Supports:
     * - text
     * - voice
     * - image
     * - audio
     * - PDF
     * - DOCX
     * - screenshots
     */
    @PostMapping(
            value = "/content/submit",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ApiResponse<ContentItem> submitInstruction(
            @RequestParam(required = false) String rawInstruction,
            @RequestParam(required = false) PlatformType platform,
            @RequestParam(required = false) String customPlatformName,
            @RequestPart(required = false) MultipartFile voiceFile,
            @RequestPart(required = false) MultipartFile attachment)
            throws Exception {

        // VALIDATION
        if ((rawInstruction == null || rawInstruction.isBlank())
                && voiceFile == null
                && attachment == null) {

            throw new RuntimeException(
                    "Please provide text, voice, or attachment.");
        }

        // SMALL TEMP FILE LIMIT
        if (attachment != null &&
                attachment.getSize() > 10 * 1024 * 1024) {

            throw new RuntimeException(
                    "Attachment too large for content generation. Max allowed size is 10MB.");
        }

        return ApiResponse.success(
                contentService.submit(
                        ContentRequest.builder()
                                .rawInstruction(rawInstruction)
                                .platform(platform)
                                .customPlatformName(customPlatformName)
                                .voiceFile(voiceFile)
                                .attachment(attachment)
                                .build()
                )
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // APPROVALS
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/content/pending")
    public ApiResponse<List<ContentItem>> getPendingApprovals() {

        return ApiResponse.success(
                contentService.getPending());
    }

    @GetMapping("/content/approved")
    public ApiResponse<List<ContentItem>> getApprovedContent() {

        return ApiResponse.success(
                contentService.getApproved());
    }

    @PostMapping("/content/approve")
    public ApiResponse<ContentItem> processApproval(
            @RequestBody ContentApprovalRequest request)
            throws JsonProcessingException {

        return ApiResponse.success(
                contentService.processApproval(request));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PUBLISH
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/content/publish")
    public ApiResponse<PublishResult> publishContent(
            @RequestBody PublishRequest request)
            throws JsonProcessingException {

        return ApiResponse.success(
                contentService.publish(request));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FILTERS
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/content/platform/{platform}")
    public ApiResponse<List<ContentItem>> getByPlatform(
            @PathVariable String platform) {

        return ApiResponse.success(
                contentService.getByPlatform(
                        PlatformType.valueOf(
                                platform.toUpperCase()
                        )
                )
        );
    }
}
