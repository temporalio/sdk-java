package io.temporal.internal.payload.visitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.command.v1.ScheduleActivityTaskCommandAttributes;
import io.temporal.api.common.v1.Memo;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Tests for {@link MessageVisitors}: message traversal with scoped context, and validation. */
public class MessageVisitorTest {

  static Payload p(String s) {
    return Payload.newBuilder().setData(ByteString.copyFromUtf8(s)).build();
  }

  static Command activity(String id, String... inputs) {
    Payloads.Builder in = Payloads.newBuilder();
    for (String s : inputs) {
      in.addPayloads(p(s));
    }
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK)
        .setScheduleActivityTaskCommandAttributes(
            ScheduleActivityTaskCommandAttributes.newBuilder().setActivityId(id).setInput(in))
        .build();
  }

  @Test
  public void visitsBuilderInPlace() {
    Memo.Builder builder = Memo.newBuilder().putFields("k", p("v"));
    List<String> entered = new ArrayList<>();
    MessageVisitors.visit(
        builder,
        MessageVisitorOptions.<Void>newBuilder()
            .setMessageVisitor(
                (current, msg) -> {
                  entered.add(msg.getDescriptorForType().getFullName());
                  return current;
                })
            .build());
    assertEquals(Arrays.asList("temporal.api.common.v1.Memo"), entered);
  }

  @Test
  public void messageVisitorMutatesInPlace() {
    RespondWorkflowTaskCompletedRequest request =
        RespondWorkflowTaskCompletedRequest.newBuilder().addCommands(activity("orig", "x")).build();

    RespondWorkflowTaskCompletedRequest result =
        MessageVisitors.visit(
            request,
            MessageVisitorOptions.<Void>newBuilder()
                .setMessageVisitor(
                    (current, msg) -> {
                      if (msg instanceof ScheduleActivityTaskCommandAttributes.Builder) {
                        ((ScheduleActivityTaskCommandAttributes.Builder) msg)
                            .setActivityId("rewritten");
                      }
                      return current;
                    })
                .build());

    assertEquals(
        "rewritten",
        result.getCommands(0).getScheduleActivityTaskCommandAttributes().getActivityId());
  }

  @Test
  public void visitsEachMessageWithScopedContext() {
    // Three commands with distinct types exercise per-command scoping and scope restoration
    // between siblings.
    RespondWorkflowTaskCompletedRequest request =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .addCommands(activity("a", "x"))
            .addCommands(
                Command.newBuilder()
                    .setCommandType(CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION)
                    .setCompleteWorkflowExecutionCommandAttributes(
                        CompleteWorkflowExecutionCommandAttributes.newBuilder())
                    .build())
            .addCommands(
                Command.newBuilder()
                    .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
                    .setRecordMarkerCommandAttributes(
                        RecordMarkerCommandAttributes.newBuilder().setMarkerName("m"))
                    .build())
            .build();

    // MessageVisitors traversal is single-threaded, so the entered messages have a stable order.
    List<String> entered = new ArrayList<>();
    List<CommandType> contextOnEnter = new ArrayList<>();

    MessageVisitorOptions<CommandType> opts =
        MessageVisitorOptions.<CommandType>newBuilder()
            .setMessageVisitor(
                (current, msg) -> {
                  entered.add(msg.getDescriptorForType().getFullName());
                  contextOnEnter.add(current);
                  return msg instanceof Command.Builder
                      ? ((Command.Builder) msg).getCommandType()
                      : current;
                })
            .build();

    MessageVisitors.visit(request, opts);

    // Exact order: the root, then each (repeated) command followed by its oneof attributes message.
    assertEquals(
        Arrays.asList(
            "temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest",
            "temporal.api.command.v1.Command",
            "temporal.api.command.v1.ScheduleActivityTaskCommandAttributes",
            "temporal.api.command.v1.Command",
            "temporal.api.command.v1.CompleteWorkflowExecutionCommandAttributes",
            "temporal.api.command.v1.Command",
            "temporal.api.command.v1.RecordMarkerCommandAttributes"),
        entered);
    // Each command is entered with scope reset to null (restored between siblings), then its own
    // type flows down into its attributes message.
    assertEquals(
        Arrays.asList(
            null,
            null,
            CommandType.COMMAND_TYPE_SCHEDULE_ACTIVITY_TASK,
            null,
            CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION,
            null,
            CommandType.COMMAND_TYPE_RECORD_MARKER),
        contextOnEnter);
  }

  @Test
  public void messageOnlyVisitorValidatesPerMessageType() {
    int maxMemoFields = 2;
    MessageVisitorOptions<Void> opts =
        MessageVisitorOptions.<Void>newBuilder()
            .setMessageVisitor(
                (current, msg) -> {
                  if (msg instanceof Memo.Builder
                      && ((Memo.Builder) msg).getFieldsCount() > maxMemoFields) {
                    throw new TestVisitorException("too many memo fields");
                  }
                  return current;
                })
            .build();

    Memo ok = Memo.newBuilder().putFields("a", p("1")).putFields("b", p("2")).build();
    MessageVisitors.visit(ok, opts); // no throw

    Memo tooMany =
        Memo.newBuilder()
            .putFields("a", p("1"))
            .putFields("b", p("2"))
            .putFields("c", p("3"))
            .build();
    assertThrows(TestVisitorException.class, () -> MessageVisitors.visit(tooMany, opts));
  }

  @Test
  public void initialContextObservedAtRoot() {
    Memo memo = Memo.newBuilder().putFields("k", p("v")).build();
    List<String> observed = new ArrayList<>();
    MessageVisitors.visit(
        memo,
        MessageVisitorOptions.<String>newBuilder()
            .setInitialContext("root")
            .setMessageVisitor(
                (current, msg) -> {
                  observed.add(current);
                  return current;
                })
            .build());
    assertEquals(Arrays.asList("root"), observed);
  }

  @Test
  public void rejectsMissingMessageVisitor() {
    assertThrows(IllegalArgumentException.class, () -> MessageVisitorOptions.newBuilder().build());
  }
}
