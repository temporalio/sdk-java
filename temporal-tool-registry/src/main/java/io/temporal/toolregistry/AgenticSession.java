package io.temporal.toolregistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.client.ActivityCompletionException;
import io.temporal.failure.ApplicationFailure;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains conversation state (messages and issues) across multiple turns of a tool-calling loop,
 * with heartbeat checkpointing for crash recovery.
 *
 * <p>Use {@link #runWithSession(SessionFn)} inside a Temporal activity to get automatic checkpoint
 * restore-on-retry and heartbeat on each turn:
 *
 * <pre>{@code
 * AgenticSession.runWithSession(session -> {
 *     session.runToolLoop(provider, registry, systemPrompt, userPrompt);
 * });
 * }</pre>
 *
 * <p>For simple non-resumable loops, use {@link ToolRegistry#runToolLoop} instead.
 */
public class AgenticSession {

  private static final Logger log = LoggerFactory.getLogger(AgenticSession.class);

  private final List<Map<String, Object>> messages = new ArrayList<>();
  private final List<Map<String, Object>> issues = new ArrayList<>();

  /** Creates an empty session. */
  public AgenticSession() {}

  /**
   * Runs the multi-turn LLM tool-calling loop, heartbeating before each turn so Temporal can track
   * progress and recover from crashes.
   *
   * <p>Must be called from within a Temporal activity (requires an active {@link
   * ActivityExecutionContext}).
   *
   * <p>If the activity is cancelled, the heartbeat call throws {@link ActivityCompletionException}
   * — this propagates out of {@code runToolLoop} and up to the caller. No explicit cancellation
   * check is needed.
   *
   * @param provider the LLM provider adapter
   * @param registry the tool registry
   * @param system the system prompt
   * @param prompt the initial user prompt (ignored if restoring from a checkpoint that already has
   *     messages)
   * @throws ActivityCompletionException if the activity is cancelled
   * @throws Exception on API or dispatch errors
   */
  public void runToolLoop(Provider provider, ToolRegistry registry, String system, String prompt)
      throws Exception {
    if (messages.isEmpty()) {
      Map<String, Object> userMsg = new java.util.LinkedHashMap<>();
      userMsg.put("role", "user");
      userMsg.put("content", prompt);
      messages.add(userMsg);
    }

    while (true) {
      // Heartbeat before each turn — throws ActivityCompletionException if cancelled.
      checkpoint();

      TurnResult result = provider.runTurn(messages, registry.definitions());
      messages.addAll(result.getNewMessages());
      if (result.isDone()) {
        return;
      }
    }
  }

  /**
   * Heartbeats the current session state to Temporal. Called automatically by {@link #runToolLoop},
   * but can also be called manually between tool dispatches.
   *
   * <p>Throws {@link ActivityCompletionException} if the activity has been cancelled.
   *
   * @throws ApplicationFailure (non-retryable) if any issue is not JSON-serializable
   */
  public void checkpoint() throws ActivityCompletionException {
    ObjectMapper mapper = new ObjectMapper();
    for (int i = 0; i < issues.size(); i++) {
      try {
        mapper.writeValueAsString(issues.get(i));
      } catch (Exception e) {
        throw ApplicationFailure.newNonRetryableFailure(
            "AgenticSession: issues["
                + i
                + "] is not JSON-serializable: "
                + e.getMessage()
                + ". Store only Map<String, Object> with JSON-serializable values.",
            "InvalidArgument");
      }
    }
    SessionCheckpoint cp = new SessionCheckpoint(messages, issues);
    Activity.getExecutionContext().heartbeat(cp);
  }

  /**
   * Runs {@code fn} inside an {@link AgenticSession}, restoring from a heartbeat checkpoint if one
   * exists (i.e., on activity retry after crash).
   *
   * <p>Must be called from within a Temporal activity.
   *
   * <p>Example:
   *
   * <pre>{@code
   * AgenticSession.runWithSession(session -> {
   *     session.runToolLoop(provider, registry, systemPrompt, userPrompt);
   * });
   * }</pre>
   *
   * @param fn the function to run with the session
   * @throws Exception propagated from {@code fn}
   */
  public static void runWithSession(SessionFn fn) throws Exception {
    AgenticSession session = new AgenticSession();
    ActivityExecutionContext ctx = Activity.getExecutionContext();
    try {
      ctx.getHeartbeatDetails(SessionCheckpoint.class)
          .ifPresent(
              cp -> {
                if (cp.version != 0 && cp.version != 1) {
                  log.warn(
                      "AgenticSession: checkpoint version {}, expected 1 — starting fresh",
                      cp.version);
                } else {
                  if (cp.version == 0) {
                    log.warn(
                        "AgenticSession: checkpoint has no version field"
                            + " — may be from an older release");
                  }
                  session.restore(cp);
                }
              });
    } catch (Exception e) {
      log.warn("AgenticSession: failed to decode checkpoint, starting fresh: {}", e.getMessage());
    }
    fn.run(session);
  }

  /** Returns an unmodifiable view of the conversation messages. */
  public List<Map<String, Object>> getMessages() {
    return Collections.unmodifiableList(messages);
  }

  /** Returns an unmodifiable view of the issues collected during the session. */
  public List<Map<String, Object>> getIssues() {
    return Collections.unmodifiableList(issues);
  }

  /** Appends an issue to the issue list. */
  public void addIssue(Map<String, Object> issue) {
    issues.add(issue);
  }

  /** Restores session state from a checkpoint. Called by {@link #runWithSession} on retry. */
  void restore(SessionCheckpoint checkpoint) {
    messages.clear();
    messages.addAll(checkpoint.messages);
    issues.clear();
    issues.addAll(checkpoint.issues);
  }
}
