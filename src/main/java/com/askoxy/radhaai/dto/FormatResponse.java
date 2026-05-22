package com.askoxy.radhaai.dto;

import lombok.*;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class FormatResponse {
    private String contentId;
    private Map<String, PlatformContent> formattedContent;    // key = "LINKEDIN", "FACEBOOK", etc.
}