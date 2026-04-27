package com.rubymusic.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;

/**
 * Global Reactor / JVM error handler — prevents transient WebSocket transport
 * errors from killing the gateway JVM.
 *
 * <p>Without this, when a downstream service (notably realtime-ws-ms) is restarted
 * while WebSocket clients are connected, the abrupt TCP close raises
 * {@code IOException: Connection reset by peer} inside Reactor Netty's
 * {@code WebsocketClientOperations.lambda$sendClose$1}. The exception can escape
 * via THREE distinct paths and each one needs its own absorption point:
 *
 * <ol>
 *   <li>{@link Hooks#onErrorDropped} — subscriber already completed when the
 *       error arrives. Easiest to handle, but covers the smallest fraction of
 *       cases. Was the only handler installed previously, which is why the
 *       gateway crashed twice already despite this class existing.</li>
 *   <li>{@link Hooks#onOperatorError} — error inside a reactive operator
 *       (e.g. websocket frame relay, decoder). When unhandled, Reactor swallows
 *       it but the underlying Netty channel may already be in a corrupt state,
 *       cascading into further uncaught failures.</li>
 *   <li>{@link Schedulers#onHandleError} — error inside a {@code Scheduler}
 *       thread (parallel / boundedElastic / single). These propagate to the
 *       thread's {@code UncaughtExceptionHandler}; if that handler is the JVM
 *       default, the thread dies silently and recurring failures eventually
 *       starve the worker pool.</li>
 *   <li>{@link Thread#setDefaultUncaughtExceptionHandler} — last-line catch-all
 *       for Reactor Netty event-loop threads (which are NOT Reactor Schedulers
 *       and bypass {@code Schedulers.onHandleError}). Without this, a single
 *       uncaught exception in an event-loop worker can take down the loop and
 *       eventually the JVM.</li>
 * </ol>
 *
 * <p>All four handlers must be in place to guarantee that a misbehaving
 * downstream service cannot bring down the API gateway.
 *
 * <p>Scope: ALL Reactor pipelines AND every JVM thread. Install once at startup.
 */
@Slf4j
@Configuration
public class ReactorErrorHandlerConfig {

    @PostConstruct
    public void installErrorHandlers() {
        installReactorOnErrorDropped();
        installReactorOnOperatorError();
        installSchedulerOnHandleError();
        installJvmDefaultUncaughtHandler();
        log.info("Reactor + JVM error handlers installed (tolerant of WS transport disconnects)");
    }

    private void installReactorOnErrorDropped() {
        Hooks.onErrorDropped(throwable -> {
            if (isTransportDisconnect(throwable)) {
                if (log.isDebugEnabled()) {
                    log.debug("onErrorDropped — silenced transport disconnect: {}", throwable.toString());
                }
                return;
            }
            log.warn("onErrorDropped — unexpected error (not propagated): {}",
                    throwable.toString(), throwable);
        });
    }

    private void installReactorOnOperatorError() {
        Hooks.onOperatorError((throwable, data) -> {
            if (isTransportDisconnect(throwable)) {
                if (log.isDebugEnabled()) {
                    log.debug("onOperatorError — silenced transport disconnect (data={}): {}",
                            data, throwable.toString());
                }
                return throwable;
            }
            log.warn("onOperatorError — operator failure (data={}): {}",
                    data, throwable.toString());
            return throwable;
        });
    }

    private void installSchedulerOnHandleError() {
        Schedulers.onHandleError((thread, throwable) -> {
            if (isTransportDisconnect(throwable)) {
                if (log.isDebugEnabled()) {
                    log.debug("Scheduler.onHandleError — silenced transport disconnect on {}: {}",
                            thread.getName(), throwable.toString());
                }
                return;
            }
            log.warn("Scheduler.onHandleError — uncaught error on {}: {}",
                    thread.getName(), throwable.toString(), throwable);
        });
    }

    private void installJvmDefaultUncaughtHandler() {
        Thread.UncaughtExceptionHandler delegate = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (isTransportDisconnect(throwable)) {
                if (log.isDebugEnabled()) {
                    log.debug("UncaughtExceptionHandler — silenced transport disconnect on {}: {}",
                            thread.getName(), throwable.toString());
                }
                return;
            }
            // Surface OutOfMemoryError loudly — the JVM is unrecoverable from this
            // point and operators should know immediately.
            if (throwable instanceof OutOfMemoryError) {
                log.error("UncaughtExceptionHandler — OutOfMemoryError on {} — JVM is unstable",
                        thread.getName(), throwable);
                if (delegate != null) delegate.uncaughtException(thread, throwable);
                return;
            }
            log.warn("UncaughtExceptionHandler — uncaught error on {} (absorbed): {}",
                    thread.getName(), throwable.toString(), throwable);
        });
    }

    /**
     * Walks the cause chain looking for transport-level disconnect signatures.
     * Defensive against cycles via the standard {@code cause == cause.getCause()} guard.
     */
    private boolean isTransportDisconnect(Throwable throwable) {
        Throwable cause = throwable;
        for (int depth = 0; cause != null && depth < 16; depth++) {
            if (matchesKnownTransportException(cause) || matchesIoExceptionMessage(cause)) {
                return true;
            }
            Throwable next = cause.getCause();
            if (next == null || next == cause) break;
            cause = next;
        }
        return false;
    }

    private boolean matchesKnownTransportException(Throwable cause) {
        String name = cause.getClass().getName();
        return name.equals("reactor.netty.channel.AbortedException")
                || name.equals("java.nio.channels.ClosedChannelException")
                || name.equals("io.netty.channel.StacklessClosedChannelException")
                || name.equals("io.netty.handler.timeout.ReadTimeoutException")
                || name.equals("io.netty.handler.codec.http.websocketx.WebSocketHandshakeException")
                || name.equals("reactor.netty.http.client.PrematureCloseException");
    }

    private boolean matchesIoExceptionMessage(Throwable cause) {
        if (!(cause instanceof IOException)) return false;
        String msg = cause.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("connection reset by peer")
                || lower.contains("broken pipe")
                || lower.contains("forcibly closed")
                || lower.contains("connection was aborted")
                || lower.contains("an existing connection was forcibly closed");
    }
}
