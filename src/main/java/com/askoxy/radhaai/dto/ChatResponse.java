package com.askoxy.radhaai.dto;

import lombok.*;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ChatResponse {
    private String sessionId;
    private String reply;
    private String platform;
    private int sourcesUsed;
}
