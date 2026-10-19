package com.officemanipulator.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the worker thread currently processing each job so a cancel request can interrupt it. */
@Component
public class JobCancellationRegistry {

    private final Map<UUID, Thread> running = new ConcurrentHashMap<>();

    public void register(UUID jobId) {
        running.put(jobId, Thread.currentThread());
    }

    public void unregister(UUID jobId) {
        running.remove(jobId);
    }

    public void interruptIfRunning(UUID jobId) {
        Thread thread = running.get(jobId);
        if (thread != null) thread.interrupt();
    }
}
