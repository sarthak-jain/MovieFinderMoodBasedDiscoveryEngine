package com.moviefinder.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class WorkflowEmitter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEmitter.class);
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;

    public WorkflowEmitter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));

        log.info("SSE client connected. Active clients: {}", emitters.size());
        return emitter;
    }

    public void broadcast(WorkflowStep step) {
        List<SseEmitter> deadEmitters = new java.util.ArrayList<>();

        for (SseEmitter emitter : emitters) {
            try {
                String json = objectMapper.writeValueAsString(step);
                emitter.send(SseEmitter.event()
                        .name("workflow-step")
                        .data(json));
            } catch (IOException e) {
                deadEmitters.add(emitter);
            }
        }

        emitters.removeAll(deadEmitters);
    }

    public void broadcastRequestStart(String traceId, String method, String path) {
        for (SseEmitter emitter : emitters) {
            try {
                String json = objectMapper.writeValueAsString(
                        java.util.Map.of(
                                "type", "REQUEST_START",
                                "traceId", traceId,
                                "method", method,
                                "path", path,
                                "timestamp", System.currentTimeMillis()
                        )
                );
                emitter.send(SseEmitter.event()
                        .name("request-start")
                        .data(json));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    public int getActiveClientCount() {
        return emitters.size();
    }
}
