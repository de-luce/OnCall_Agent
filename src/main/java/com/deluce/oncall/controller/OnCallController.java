package com.deluce.oncall.controller;

import com.deluce.oncall.dto.*;
import com.deluce.oncall.service.OnCallService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

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
        AtomicBoolean cancelled = new AtomicBoolean(false);

        Runnable markCancelled = () -> cancelled.set(true);
        emitter.onCompletion(markCancelled);
        emitter.onTimeout(markCancelled);
        emitter.onError(ex -> cancelled.set(true));

        Flux<String> stream = onCallService.chatStream(request, cancelled);
        Disposable subscription = stream.subscribe(
                chunk -> {
                    if (cancelled.get()) {
                        return;
                    }
                    try {
                        emitter.send(SseEmitter.event().data(chunk));
                    } catch (IOException | IllegalStateException e) {
                        cancelled.set(true);
                        emitter.complete();
                    }
                },
                error -> {
                    if (!cancelled.get()) {
                        emitter.completeWithError(error);
                    }
                },
                () -> {
                    if (!cancelled.get()) {
                        emitter.complete();
                    }
                }
        );

        emitter.onCompletion(subscription::dispose);
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

    @GetMapping("/history/sessions")
    public ApiResult<HistorySessionListResponse> historySessions(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ApiResult.ok(onCallService.listHistorySessions(limit, offset));
    }

    @GetMapping("/history/sessions/{sessionId}/messages")
    public ApiResult<HistoryMessagesResponse> historyMessages(@PathVariable String sessionId) {
        return ApiResult.ok(onCallService.getHistoryMessages(sessionId));
    }

    @DeleteMapping("/history/sessions/{sessionId}")
    public ResponseEntity<ApiResult<Boolean>> deleteHistorySession(@PathVariable String sessionId) {
        if (!onCallService.deleteHistorySession(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResult.fail("会话不存在"));
        }
        return ResponseEntity.ok(ApiResult.ok(true));
    }

    @GetMapping("/health")
    public ApiResult<String> health() {
        return ApiResult.ok("OnCall Agent is running");
    }
}
