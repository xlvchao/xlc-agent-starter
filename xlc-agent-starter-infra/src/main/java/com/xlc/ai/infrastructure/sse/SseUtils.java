package com.xlc.ai.infrastructure.sse;

import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class SseUtils {

    public static SseEmitter createSseEmitter(long timeout) {
        return new SseEmitter(timeout) {
            @Override
            protected void extendResponse(ServerHttpResponse outputMessage) {
                super.extendResponse(outputMessage);
                outputMessage.getHeaders().set("Cache-Control", "no-cache, no-transform");
                outputMessage.getHeaders().set("X-Accel-Buffering", "no");
                outputMessage.getHeaders().set("Connection", "keep-alive");
            }
        };
    }
}
