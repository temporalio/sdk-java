package io.temporal.workflowcheck;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

public class LoggingCaptureHandler extends Handler {
  private final List<LogRecord> records = new ArrayList<>();

  public LoggingCaptureHandler() {
    setFormatter(new SimpleFormatter());
  }

  @Override
  public synchronized void publish(LogRecord record) {
    records.add(record);
  }

  @Override
  public void flush() {}

  @Override
  public void close() throws SecurityException {}

  public synchronized List<LogRecord> collectRecords() {
    return new ArrayList<>(records);
  }
}
