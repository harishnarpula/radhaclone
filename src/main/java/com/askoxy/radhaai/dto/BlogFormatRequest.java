package com.askoxy.radhaai.dto;



import lombok.*;
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlogFormatRequest {

    private String entityId;
    private String entityType;      // "VIDEO" or "CONTENT"
    private boolean generateImage;
}