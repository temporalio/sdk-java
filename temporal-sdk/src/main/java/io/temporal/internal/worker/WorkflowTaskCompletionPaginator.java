package io.temporal.internal.worker;

import io.temporal.api.command.v1.Command;
import io.temporal.api.workflowservice.v1.RespondWorkflowTaskCompletedRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits an oversized {@link RespondWorkflowTaskCompletedRequest} into pages that each stay under
 * the gRPC request size limit, so a completion carrying more command bytes than a single request
 * can hold is delivered across multiple requests sharing one task token. The server buffers the
 * commands of the intermediate pages and merges them with the final page when it arrives.
 */
final class WorkflowTaskCompletionPaginator {

  /**
   * Maximum encoded size of a single completion page, kept below the ~4 MiB gRPC frame limit. This
   * per-page cap is distinct from the namespace's limit on the recombined completion size.
   *
   * <p>Pages are packed by summing command body sizes only; the 512 KiB of headroom below 4 MiB
   * absorbs everything that sum omits: the per-request overhead (task token, identity, namespace)
   * and the per-command wire framing (a field tag plus a length varint, up to 6 bytes each). At the
   * server's default per-workflow history-count limit (~51,200 events), worst-case framing is ~300
   * KiB, so this headroom covers even a page of many tiny commands and lets us skip per-command
   * accounting.
   */
  static final int MAX_PAGE_BYTES = 4 * 1024 * 1024 - 512 * 1024;

  /** The result of splitting a completion: zero or more intermediate pages plus the final page. */
  static final class Pages {
    final List<RespondWorkflowTaskCompletedRequest> intermediatePages;
    final RespondWorkflowTaskCompletedRequest finalPage;

    Pages(
        List<RespondWorkflowTaskCompletedRequest> intermediatePages,
        RespondWorkflowTaskCompletedRequest finalPage) {
      this.intermediatePages = intermediatePages;
      this.finalPage = finalPage;
    }

    /** True when the completion was split; false when the final page should be sent as-is. */
    boolean isPaginated() {
      return !intermediatePages.isEmpty();
    }
  }

  /**
   * Splits {@code request} into intermediate pages that each stay under {@code maxPageBytes} by
   * distributing its commands across them in order. The final page carries the remaining metadata
   * and messages, and its page number is the count of intermediate pages.
   *
   * <p>Returns a {@link Pages} with no intermediate pages (send {@code request} as-is) when the
   * request already fits, has no commands to distribute, or has a single command that alone exceeds
   * a page (which the server then rejects).
   */
  static Pages paginate(RespondWorkflowTaskCompletedRequest request, int maxPageBytes) {
    if (request.getSerializedSize() <= maxPageBytes) {
      return new Pages(new ArrayList<>(), request);
    }

    List<Command> commands = request.getCommandsList();
    // Only commands can be split across pages, so pagination cannot help when there are none, or
    // when
    // a single command alone exceeds a page.
    if (commands.isEmpty()) {
      return new Pages(new ArrayList<>(), request);
    }
    for (Command command : commands) {
      if (command.getSerializedSize() > maxPageBytes) {
        return new Pages(new ArrayList<>(), request);
      }
    }

    List<RespondWorkflowTaskCompletedRequest> intermediatePages = new ArrayList<>();
    List<Command> current = new ArrayList<>();
    int currentLen = 0;
    for (Command command : commands) {
      int commandLen = command.getSerializedSize();
      if (!current.isEmpty() && currentLen + commandLen > maxPageBytes) {
        intermediatePages.add(newIntermediatePage(request, current, intermediatePages.size()));
        current = new ArrayList<>();
        currentLen = 0;
      }
      currentLen += commandLen;
      current.add(command);
    }
    if (!current.isEmpty()) {
      intermediatePages.add(newIntermediatePage(request, current, intermediatePages.size()));
    }

    RespondWorkflowTaskCompletedRequest finalPage =
        request.toBuilder()
            .clearCommands()
            .setPageNumber(intermediatePages.size())
            .setIntermediatePage(false)
            .build();
    return new Pages(intermediatePages, finalPage);
  }

  private static RespondWorkflowTaskCompletedRequest newIntermediatePage(
      RespondWorkflowTaskCompletedRequest request, List<Command> commands, int pageNumber) {
    return RespondWorkflowTaskCompletedRequest.newBuilder()
        .setTaskToken(request.getTaskToken())
        .setIdentity(request.getIdentity())
        .setNamespace(request.getNamespace())
        .setIntermediatePage(true)
        .setPageNumber(pageNumber)
        .addAllCommands(commands)
        .build();
  }

  private WorkflowTaskCompletionPaginator() {}
}
