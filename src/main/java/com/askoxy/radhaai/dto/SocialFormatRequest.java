package com.askoxy.radhaai.dto;

import lombok.*;
import java.util.List;

@Data @NoArgsConstructor @AllArgsConstructor
public class SocialFormatRequest {
    private String entityId;
    private List<String> platforms;
    private String editedContent;
}