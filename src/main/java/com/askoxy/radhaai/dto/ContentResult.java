package com.askoxy.radhaai.dto;

import lombok.*;
import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ContentResult {

    // flat single-topic fields (backward compatible)
    private String title;
    private String body;
    private String hashtags;
    private String callToAction;

    // grouped multi-section fields (when isGrouped = true)
    private String intro;
    private List<ContentSection> sections;
    private String closing;
    private Boolean isGrouped;
}