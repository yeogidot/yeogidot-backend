package com.yeogidot.yeogidot.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only active inside the two measured HTTP requests; no payloads or exception messages are logged. */
public final class UploadTiming {
    private static final Logger log = LoggerFactory.getLogger(UploadTiming.class);
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private UploadTiming() {}

    public record Context(String requestId, String operation, int photoIndex) {
        public Context photo(int index) { return new Context(requestId, operation, index); }
    }

    public static Context current() { return CURRENT.get(); }

    public static Scope bind(Context context) {
        Context previous = CURRENT.get();
        if (context == null) CURRENT.remove(); else CURRENT.set(context);
        return () -> {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        };
    }

    public static Scope photo(int index) {
        Context context = current();
        return bind(context == null ? null : context.photo(index));
    }

    public static Span start(String stage) { return new Span(current(), stage); }

    public static void elapsed(String stage, long started, String outcome) {
        emit(current(), stage, started, outcome);
    }

    private static void emit(Context context, String stage, long started, String outcome) {
        if (context == null) return;
        double millis = (System.nanoTime() - started) / 1_000_000.0;
        log.info("[UPLOAD_TIMING] request_id={} operation={} photo_index={} stage={} duration_ms={} outcome={}",
                context.requestId(), context.operation(), context.photoIndex(), stage,
                Math.round(millis * 1000.0) / 1000.0, outcome);
    }

    /** Cumulative service-entry -> afterCompletion, NOT the duration of the DB commit alone. */
    public static void watchTransaction(long serviceStarted) {
        Context context = current();
        if (context == null || !TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                String outcome = switch (status) {
                    case STATUS_COMMITTED -> "COMMITTED";
                    case STATUS_ROLLED_BACK -> "ROLLED_BACK";
                    default -> "UNKNOWN";
                };
                emit(context, "service_to_transaction_completion", serviceStarted, outcome);
            }
        });
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    public static final class Span implements AutoCloseable {
        private final Context context;
        private final String stage;
        private final long started = System.nanoTime();
        private boolean success;

        private Span(Context context, String stage) { this.context = context; this.stage = stage; }
        public void success() { success = true; }
        @Override public void close() { emit(context, stage, started, success ? "SUCCESS" : "FAILED"); }
    }
}
