package com.deluce.oncall.dto;

import java.util.List;

public record OpsResponse(
        String sessionId,
        String rootCause,
        String recommendation,
        List<String> executedSteps,
        String report
) {
}
