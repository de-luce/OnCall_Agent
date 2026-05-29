package com.deluce.oncall.dto;

public record UploadResponse(
        String fileName,
        int chunkCount,
        String message
) {
}
