package io.github.rhinolens.debugdemo.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.github.rhinolens.debugdemo.debug.DebugSession;
import io.github.rhinolens.debugdemo.debug.SessionRegistry;
import io.github.rhinolens.debugdemo.debug.SessionState;

@RestController
@RequestMapping("/api")
public class DebugController {

    public record StartRequest(String source, List<Integer> breakpoints, boolean pauseOnStart) {
    }

    public record EvaluateRequest(String expression, int frame) {
    }

    public record ScopeObject(String name, String type, String value) {
    }

    public record Sample(String source, List<ScopeObject> objects) {
    }

    private final SessionRegistry registry;

    public DebugController(SessionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/sample")
    public Sample sample() {
        try {
            String source = new ClassPathResource("sample/rule.js").getContentAsString(StandardCharsets.UTF_8);
            List<ScopeObject> objects = SessionRegistry.javaObjects().entrySet().stream()
                    .map(e -> new ScopeObject(e.getKey(), e.getValue().getClass().getSimpleName(),
                            e.getValue().toString()))
                    .toList();
            return new Sample(source, objects);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @PostMapping("/sessions")
    public Map<String, String> start(@RequestBody StartRequest request) {
        List<Integer> breakpoints = request.breakpoints() == null ? List.of() : request.breakpoints();
        DebugSession session = registry.start(request.source(), breakpoints, request.pauseOnStart());
        return Map.of("id", session.id());
    }

    @GetMapping(path = "/sessions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        DebugSession session = session(id);
        SseEmitter emitter = new SseEmitter(0L);
        AtomicReference<Consumer<SessionState>> self = new AtomicReference<>();
        Consumer<SessionState> listener = state -> {
            try {
                emitter.send(SseEmitter.event().data(state, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                session.removeListener(self.get());
            }
        };
        self.set(listener);
        emitter.onCompletion(() -> session.removeListener(listener));
        emitter.onError(e -> session.removeListener(listener));
        session.addListener(listener);
        listener.accept(session.state());
        return emitter;
    }

    @PutMapping("/sessions/{id}/breakpoints")
    public void breakpoints(@PathVariable String id, @RequestBody List<Integer> lines) {
        session(id).setBreakpoints(lines);
    }

    @PostMapping("/sessions/{id}/commands/{action}")
    public ResponseEntity<Map<String, Object>> command(@PathVariable String id, @PathVariable String action) {
        DebugSession.Action parsed;
        try {
            parsed = DebugSession.Action.valueOf(action.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown action " + action);
        }
        boolean accepted = session(id).command(parsed);
        return ResponseEntity.status(accepted ? HttpStatus.OK : HttpStatus.CONFLICT)
                .body(Map.of("accepted", accepted));
    }

    @PostMapping("/sessions/{id}/evaluate")
    public Map<String, String> evaluate(@PathVariable String id, @RequestBody EvaluateRequest request) {
        return Map.of("value", session(id).evaluate(request.expression(), request.frame()));
    }

    private DebugSession session(String id) {
        return registry.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
