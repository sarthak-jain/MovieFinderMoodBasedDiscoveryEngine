package com.moviefinder.controller;

import com.moviefinder.workflow.WorkflowEmitter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final WorkflowEmitter workflowEmitter;

    public WorkflowController(WorkflowEmitter workflowEmitter) {
        this.workflowEmitter = workflowEmitter;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamWorkflow() {
        return workflowEmitter.createEmitter();
    }

    @GetMapping("/status")
    public java.util.Map<String, Object> getStatus() {
        return java.util.Map.of(
                "activeClients", workflowEmitter.getActiveClientCount(),
                "status", "running"
        );
    }
}
