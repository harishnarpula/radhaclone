package com.askoxy.radhaai.dto;

import lombok.*;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PublishRequest {
    private String contentId;
    /** Channels: LINKEDIN, INSTAGRAM, BLOG, EMAIL, WHATSAPP */
    private List<String> channels;
}
