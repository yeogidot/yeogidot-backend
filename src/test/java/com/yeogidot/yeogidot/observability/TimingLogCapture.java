package com.yeogidot.yeogidot.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class TimingLogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
    final List<String> lines = new CopyOnWriteArrayList<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(UploadTiming.class);
    private final Level previous = logger.getLevel();

    TimingLogCapture() {
        start();
        logger.setLevel(Level.INFO);
        logger.addAppender(this);
    }

    @Override protected void append(ILoggingEvent event) { lines.add(event.getFormattedMessage()); }
    @Override public void close() {
        logger.detachAppender(this);
        logger.setLevel(previous);
        stop();
    }
}
