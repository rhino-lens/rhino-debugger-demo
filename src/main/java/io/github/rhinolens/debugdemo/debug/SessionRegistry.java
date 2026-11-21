package io.github.rhinolens.debugdemo.debug;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

import io.github.rhinolens.debugdemo.sample.Order;
import io.github.rhinolens.debugdemo.sample.PricingService;
import jakarta.annotation.PreDestroy;

/** Starts debug sessions, one at a time: starting a new one stops the old. */
@Service
public class SessionRegistry {

    public static final String SOURCE_NAME = "rule.js";

    private final Map<String, DebugSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService scriptThreads = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "script-debug");
        t.setDaemon(true);
        return t;
    });

    public synchronized DebugSession start(String source, Collection<Integer> breakpoints, boolean pauseOnStart) {
        sessions.values().forEach(s -> s.command(DebugSession.Action.STOP));
        sessions.clear();
        DebugSession session = new DebugSession(UUID.randomUUID().toString(), source, SOURCE_NAME,
                javaObjects(), breakpoints, pauseOnStart);
        sessions.put(session.id(), session);
        scriptThreads.submit(session::run);
        return session;
    }

    public Optional<DebugSession> find(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    /** The Java objects scripts see, made fresh for each run. */
    public static Map<String, Object> javaObjects() {
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("order", Order.sample());
        objects.put("pricing", new PricingService());
        return objects;
    }

    @PreDestroy
    void shutdown() {
        sessions.values().forEach(s -> s.command(DebugSession.Action.STOP));
        scriptThreads.shutdownNow();
    }
}
