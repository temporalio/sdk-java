/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
