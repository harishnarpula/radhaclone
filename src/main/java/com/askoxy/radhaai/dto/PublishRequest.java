package com.askoxy.radhaai.dto;

import lombok.*;
import java.util.List;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PublishRequest {
    private String contentId;
    private List<String> channels;
    // pre-formatted content from /format step (admin may have edited)
    private Map<String, PlatformContent> formattedContent;
}