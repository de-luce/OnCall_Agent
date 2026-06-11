package com.deluce.oncall.controller;

import com.deluce.oncall.dto.*;
import com.deluce.oncall.service.OnCallService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;

@RestController
@RequestMapping("/api")
public class OnCallController {

    private final OnCallService onCallService;

    public OnCallController(OnCallService onCallService) {
        this.onCallService = onCallService;
    }

    @PostMapping("/chat")
    public ApiResult<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResult.ok(onCallService.chat(request));
    }

    @PostMapping(value = "/chat_stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        Flux<String> stream = onCallService.chatStream(request);
        stream.subscribe(
                chunk -> {
                    try {
                        emitter.send(SseEmitter.event().data(chunk));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );
        return emitter;
    }

    @PostMapping("/upload_file")
    public ApiResult<UploadResponse> uploadFile(@RequestParam("file") MultipartFile file) throws Exception {
        return ApiResult.ok(onCallService.uploadFile(file));
    }

    @PostMapping("/ai_ops")
    public ApiResult<OpsResponse> aiOps(@Valid @RequestBody OpsRequest request) {
        return ApiResult.ok(onCallService.aiOps(request));
    }

    @GetMapping("/knowledge/keywords")
    public ApiResult<KnowledgeCatalogResponse> knowledgeKeywords() {
        return ApiResult.ok(onCallService.knowledgeCatalog());
    }

    @PostMapping("/knowledge/chat")
    public ApiResult<ChatResponse> knowledgeChat(@Valid @RequestBody ChatRequest request) {
        return ApiResult.ok(onCallService.knowledgeChat(request));
    }

    @GetMapping("/health")
    public ApiResult<String> health() {
        return ApiResult.ok("OnCall Agent is running");
    }
}
