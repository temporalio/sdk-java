package io.temporal.internal.testservice;

import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.EventType;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.StringTokenizer;

public class LinkConverter {

  private static final String linkPathFormat = "temporal:///namespaces/%s/workflows/%s/%s/history";
  private static final String linkQueryFormat =
      "?eventID=%d&eventType=%s&referenceType=EventReference";

  public static io.temporal.api.nexus.v1.Link WorkflowEventToNexusLink(Link.WorkflowEvent we) {
    String url =
        String.format(linkPathFormat, we.getNamespace(), we.getWorkflowId(), we.getRunId());
    if (we.hasEventRef()) {
      url +=
          String.format(
              linkQueryFormat, we.getEventRef().getEventId(), we.getEventRef().getEventType());
    }
    return io.temporal.api.nexus.v1.Link.newBuilder()
        .setUrl(url)
        .setType(we.getDescriptorForType().getFullName())
        .build();
  }

  public static Link NexusLinkToWorkflowEvent(io.temporal.api.nexus.v1.Link nexusLink) {
    Link.Builder link = Link.newBuilder();
    try {
      URI uri = new URI(nexusLink.getUrl());

      StringTokenizer st = new StringTokenizer(uri.getPath(), "/");
      st.nextToken(); // /namespaces/
      String namespace = st.nextToken();
      st.nextToken(); // /workflows/
      String workflowID = st.nextToken();
      String runID = st.nextToken();

      Link.WorkflowEvent.Builder we =
          Link.WorkflowEvent.newBuilder()
              .setNamespace(namespace)
              .setWorkflowId(workflowID)
              .setRunId(runID);

      if (uri.getQuery() != null) {
        Link.WorkflowEvent.EventReference.Builder eventRef =
            Link.WorkflowEvent.EventReference.newBuilder();
        st = new StringTokenizer(uri.getQuery(), "&");
        while (st.hasMoreTokens()) {
          String[] param = st.nextToken().split("=");
          switch (param[0]) {
            case "eventID":
              eventRef.setEventId(Long.parseLong(param[1]));
              continue;
            case "eventType":
              eventRef.setEventType(EventType.valueOf(param[1]));
          }
        }
        we.setEventRef(eventRef);
      }
    } catch (URISyntaxException e) {
      // Swallow un-parsable links since they are not critical to processing
      return null;
    }
    return link.build();
  }
}
