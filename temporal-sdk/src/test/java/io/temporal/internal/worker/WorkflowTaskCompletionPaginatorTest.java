package io.temporal.internal.worker;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RecordMarkerCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class WorkflowTaskCompletionPaginatorTest {

  private static Command commandWithPayload(int dataSize) {
    return Command.newBuilder()
        .setCommandType(CommandType.COMMAND_TYPE_RECORD_MARKER)
        .setRecordMarkerCommandAttributes(
            RecordMarkerCommandAttributes.newBuilder()
                .setMarkerName("marker")
                .putDetails(
                    "data",
                    Payloads.newBuilder()
                        .addPayloads(
                            Payload.newBuilder().setData(ByteString.copyFrom(new byte[dataSize])))
                        .build()))
        .build();
  }

  private static RespondWorkflowTaskCompletedRequest requestWith(List<Command> commands) {
    return RespondWorkflowTaskCompletedRequest.newBuilder()
        .setTaskToken(ByteString.copyFromUtf8("task-token"))
        .setIdentity("identity")
        .setNamespace("namespace")
        .addAllCommands(commands)
        .build();
  }

  @Test
  public void completionWithinLimitIsASingleFinalPage() {
    RespondWorkflowTaskCompletedRequest request =
        requestWith(java.util.Collections.singletonList(commandWithPayload(16)));

    WorkflowTaskCompletionPaginator.Pages pages =
        WorkflowTaskCompletionPaginator.paginate(request, 4096);

    assertFalse(pages.isPaginated());
    assertEquals(0, pages.finalPage.getPageNumber());
    assertFalse(pages.finalPage.getIntermediatePage());
    assertEquals(1, pages.finalPage.getCommandsCount());
  }

  @Test
  public void largeCompletionSplitsCommandsAcrossPages() {
    int maxPageBytes = 1024;
    int commandCount = 6;
    List<Command> commands = new ArrayList<>();
    for (int i = 0; i < commandCount; i++) {
      commands.add(commandWithPayload(400));
    }
    RespondWorkflowTaskCompletedRequest request = requestWith(commands);
    assertTrue(request.getSerializedSize() > maxPageBytes);

    WorkflowTaskCompletionPaginator.Pages pages =
        WorkflowTaskCompletionPaginator.paginate(request, maxPageBytes);

    assertTrue(pages.isPaginated());
    assertFalse(pages.finalPage.getIntermediatePage());
    assertEquals(0, pages.finalPage.getCommandsCount());
    assertEquals(pages.intermediatePages.size(), pages.finalPage.getPageNumber());
    assertTrue(pages.finalPage.getSerializedSize() <= maxPageBytes);
    assertEquals(ByteString.copyFromUtf8("task-token"), pages.finalPage.getTaskToken());

    int totalCommands = 0;
    for (int i = 0; i < pages.intermediatePages.size(); i++) {
      RespondWorkflowTaskCompletedRequest page = pages.intermediatePages.get(i);
      assertTrue(page.getIntermediatePage());
      assertEquals(i, page.getPageNumber());
      assertEquals(ByteString.copyFromUtf8("task-token"), page.getTaskToken());
      assertTrue(
          "intermediate page " + i + " over limit", page.getSerializedSize() <= maxPageBytes);
      totalCommands += page.getCommandsCount();
    }
    // Every command is preserved exactly once across the intermediate pages.
    assertEquals(commandCount, totalCommands);
  }

  @Test
  public void singleCommandLargerThanAPageIsNotSplit() {
    int maxPageBytes = 1024;
    RespondWorkflowTaskCompletedRequest request =
        requestWith(java.util.Collections.singletonList(commandWithPayload(4096)));

    WorkflowTaskCompletionPaginator.Pages pages =
        WorkflowTaskCompletionPaginator.paginate(request, maxPageBytes);

    assertFalse(pages.isPaginated());
    assertEquals(1, pages.finalPage.getCommandsCount());
    assertFalse(pages.finalPage.getIntermediatePage());
  }

  @Test
  public void noCommandsIsNotSplit() {
    RespondWorkflowTaskCompletedRequest request =
        RespondWorkflowTaskCompletedRequest.newBuilder()
            .setTaskToken(ByteString.copyFromUtf8("task-token"))
            .setIdentity("identity")
            .setNamespace("namespace")
            .build();

    WorkflowTaskCompletionPaginator.Pages pages =
        WorkflowTaskCompletionPaginator.paginate(request, 1);

    assertFalse(pages.isPaginated());
    assertEquals(0, pages.finalPage.getCommandsCount());
  }
}
