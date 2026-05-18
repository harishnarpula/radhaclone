package com.askoxy.radhaai.dto;

import lombok.*;
import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PublishResult {
    private String contentId;
    private boolean success;
    private Map<String, String> channelResults;
    private String message;
}
