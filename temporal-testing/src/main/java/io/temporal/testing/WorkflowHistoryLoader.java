package io.temporal.testing;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Preconditions;
import com.google.common.io.CharStreams;
import io.temporal.common.Experimental;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.WorkflowExecutionUtils;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.nio.file.Files;

/**
 * Expose methods to read and deserialize workflow execution history from json.<br>
 * To be used with {@link WorkflowReplayer}
 *
 * <p>2021-11-29 Experimental because the user facing interface to history replay functionality is
 * actively evolving.
 */
@Experimental
public final class WorkflowHistoryLoader {
  private WorkflowHistoryLoader() {}

  public static WorkflowExecutionHistory readHistoryFromResource(String resourceFileName)
      throws IOException {
    ClassLoader classLoader = WorkflowExecutionUtils.class.getClassLoader();
    URL resource = classLoader.getResource(resourceFileName);
    Preconditions.checkArgument(resource != null, "File %s can't be found", resourceFileName);
    String historyUrl = resource.getFile();
    File historyFile = new File(historyUrl);
    return readHistory(historyFile);
  }

  public static WorkflowExecutionHistory readHistory(File historyFile) throws IOException {
    try (Reader reader = Files.newBufferedReader(historyFile.toPath(), UTF_8)) {
      String jsonHistory = CharStreams.toString(reader);
      return WorkflowExecutionHistory.fromJson(jsonHistory);
    }
  }
}
