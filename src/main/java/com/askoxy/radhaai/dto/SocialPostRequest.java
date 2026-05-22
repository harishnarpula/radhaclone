package com.askoxy.radhaai.dto;

import lombok.*;
import java.util.Map;
import com.askoxy.radhaai.dto.PlatformContent;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SocialPostRequest {
    private String entityId;
    private Map<String, PlatformContent> approvedBodies;
}