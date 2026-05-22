package com.askoxy.radhaai.dto;

import lombok.*;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlogFormatResult {

    private String entityId;        // was videoId — RENAME THIS
    private String entityType;      // "VIDEO" or "CONTENT"  ← ADD THIS
    private String title;
    private String description;
    private String socialMediaCaptions;
    private String addedBy;
    private String videoUrl;
    private String videoFileUrl;
    private String imageUrl;
    private String status;
    private String blogPostId;
}