package com.deluce.oncall.dto;

import jakarta.validation.constraints.NotBlank;

public record OpsRequest(
        @NotBlank String alertMessage,
        String sessionId,
        String serviceName
) {
}
