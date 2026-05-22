package com.askoxy.radhaai.dto;

import lombok.*;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SocialPostResult {
    private String entityId;
    private Map<String, String> results;
}