package com.deluce.oncall.controller;

import com.deluce.oncall.dto.*;
import com.deluce.oncall.service.OnCallService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class OnCallController {

    private static final Logger log = LoggerFactory.getLogger(OnCallController.class);

    private final OnCallService onCallService;

    public OnCallController(OnCallService onCallService) {
        this.onCallService = onCallService;
    }

    @PostMapping("/chat")
    public ApiResult<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("[收到消息] POST /api/chat sessionId={}, message={}", request.sessionId(), request.message());
        try {
            ChatResponse response = onCallService.chat(request);
            log.info("[返回消息] POST /api/chat sessionId={}, answerLength={}",
                    response.sessionId(), response.answer() != null ? response.answer().length() : 0);
            return ApiResult.ok(response);
        } catch (Exception e) {
            log.error("[消息失败] POST /api/chat sessionId={}, error={}", request.sessionId(), e.getMessage(), e);
            throw e;
        }
    }

    @PostMapping(value = "/chat_stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@Valid @RequestBody ChatRequest request) {
        log.info("[收到消息] POST /api/chat_stream sessionId={}, message={}", request.sessionId(), request.message());
        return onCallService.chatStream(request)
                .doOnSubscribe(s -> log.info("[流式开始] sessionId={}", request.sessionId()))
                .doOnNext(chunk -> log.debug("[流式片段] sessionId={}, chunk={}", request.sessionId(), chunk))
                .doOnComplete(() -> log.info("[流式完成] sessionId={}", request.sessionId()))
                .doOnError(e -> log.error("[流式失败] sessionId={}, error={}", request.sessionId(), e.getMessage(), e))
                .onErrorResume(e -> Flux.just("错误: " + e.getMessage()))
                .map(chunk -> ServerSentEvent.builder(chunk).build());
    }

    @PostMapping("/upload_file")
    public ApiResult<UploadResponse> uploadFile(@RequestParam("file") MultipartFile file) throws Exception {
        log.info("[上传文件] fileName={}, size={}", file.getOriginalFilename(), file.getSize());
        return ApiResult.ok(onCallService.uploadFile(file));
    }

    @PostMapping("/ai_ops")
    public ApiResult<OpsResponse> aiOps(@Valid @RequestBody OpsRequest request) {
        log.info("[运维排查] service={}, alert={}", request.serviceName(), request.alertMessage());
        return ApiResult.ok(onCallService.aiOps(request));
    }

    @GetMapping("/knowledge/keywords")
    public ApiResult<KnowledgeCatalogResponse> knowledgeKeywords() {
        return ApiResult.ok(onCallService.knowledgeCatalog());
    }

    @PostMapping("/knowledge/chat")
    public ApiResult<ChatResponse> knowledgeChat(@Valid @RequestBody ChatRequest request) {
        log.info("[知识库问答] sessionId={}, message={}", request.sessionId(), request.message());
        return ApiResult.ok(onCallService.knowledgeChat(request));
    }

    @GetMapping("/health")
    public ApiResult<String> health() {
        return ApiResult.ok("OnCall Agent is running");
    }
}
