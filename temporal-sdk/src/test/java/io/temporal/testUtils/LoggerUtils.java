package io.temporal.testUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

public class LoggerUtils {
  public static SilenceLoggers silenceLoggers(Class<?>... classes) {
    return new SilenceLoggers(classes);
  }

  public static class SilenceLoggers implements AutoCloseable {
    private final List<Logger> loggers;
    List<Level> oldLogLevels;

    public SilenceLoggers(Class<?>... classes) {
      loggers =
          Arrays.stream(classes)
              .map(LoggerFactory::getLogger)
              .filter(Logger.class::isInstance)
              .map(Logger.class::cast)
              .collect(Collectors.toList());
      oldLogLevels = new ArrayList<>();
      for (Logger logger : loggers) {
        oldLogLevels.add(logger.getLevel());
        logger.setLevel(Level.OFF);
      }
    }

    @Override
    public void close() {
      for (int i = 0; i < loggers.size(); i++) {
        loggers.get(i).setLevel(oldLogLevels.get(i));
      }
    }
  }
}
