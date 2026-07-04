package org.aura.aura.streaming;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// The threading policy for SSE streaming, kept out of the servlet request path on purpose.
@Configuration
public class StreamingAsyncConfig {

    // Bean name is a contract: TicketStreamingService injects THIS pool by name so it can never
    // accidentally bind to the servlet/@Async default. Referencing one constant keeps the wiring
    // typo-proof.
    public static final String SSE_EXECUTOR = "sseExecutor";

    // SseEmitter timeout. Deliberately generous — well above worst-case Sonnet generation — so a
    // slow-but-healthy answer is never guillotined mid-sentence. It is a SAFETY NET, not the
    // expected lifetime: a genuinely hung upstream still can't pin a connection (and its pump
    // thread) open forever; at this deadline Spring completes the emitter and the thread frees up.
    public static final long SSE_TIMEOUT_MS = 120_000L;

    // A DEDICATED pool, NOT Tomcat's request threads. An SSE pump blocks for the entire
    // generation (seconds per ticket), so running pumps on servlet threads would tie up the very
    // threads that accept new HTTP connections — a handful of concurrent streams could stall all
    // plain request handling. Isolating streaming here contains that blast radius.
    @Bean(SSE_EXECUTOR)
    public ThreadPoolTaskExecutor sseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // core 4 / max 8: a small support service, not a fan-out farm. The pool grows to 8 only
        // after the queue fills, then stops — an explicit ceiling on concurrent paid generations.
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        // BOUNDED queue = backpressure over a memory leak. If all 8 threads are busy and 32 more
        // streams are already waiting, the pool REJECTS further submissions (fast failure) instead
        // of queueing unboundedly and blowing the heap under a load spike.
        executor.setQueueCapacity(32);
        // Named threads ("sse-1", "sse-2", ...) so a stuck stream is instantly identifiable in a
        // thread dump or log line versus an anonymous "pool-3-thread-7".
        executor.setThreadNamePrefix("sse-");
        executor.initialize();
        return executor;
    }
}
