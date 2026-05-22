package com.askoxy.radhaai.dto;

import lombok.*;
import java.util.List;
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormatRequest {

    private String entityId;        // was contentId — RENAME THIS
    private String entityType;      // "VIDEO" or "CONTENT"  ← ADD THIS
    private List<String> platforms;
    private String editedContent;   // ← ADD THIS
}