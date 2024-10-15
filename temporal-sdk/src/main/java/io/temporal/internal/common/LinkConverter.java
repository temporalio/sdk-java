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

package io.temporal.internal.common;

import static io.temporal.internal.common.ProtoEnumNameUtils.EVENT_TYPE_PREFIX;
import static io.temporal.internal.common.ProtoEnumNameUtils.simplifiedToUniqueName;

import io.temporal.api.common.v1.Link;
import io.temporal.api.enums.v1.EventType;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.StringTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LinkConverter {

  private static final Logger log = LoggerFactory.getLogger(LinkConverter.class);

  private static final String linkPathFormat = "temporal:///namespaces/%s/workflows/%s/%s/history";

  public static io.temporal.api.nexus.v1.Link workflowEventToNexusLink(Link.WorkflowEvent we) {
    try {
      String url =
          String.format(
              linkPathFormat,
              URLEncoder.encode(we.getNamespace(), StandardCharsets.UTF_8.toString()),
              URLEncoder.encode(we.getWorkflowId(), StandardCharsets.UTF_8.toString()),
              URLEncoder.encode(we.getRunId(), StandardCharsets.UTF_8.toString()));

      if (we.hasEventRef()) {
        url += "?";
        if (we.getEventRef().getEventId() > 0) {
          url += "eventID=" + we.getEventRef().getEventId() + "&";
        }
        url +=
            "eventType="
                + URLEncoder.encode(
                    we.getEventRef().getEventType().name(), StandardCharsets.UTF_8.toString())
                + "&";
        url += "referenceType=EventReference";
      }

      return io.temporal.api.nexus.v1.Link.newBuilder()
          .setUrl(url)
          .setType(we.getDescriptorForType().getFullName())
          .build();
    } catch (Exception e) {
      log.error("Failed to encode Nexus link URL", e);
    }
    return null;
  }

  public static Link nexusLinkToWorkflowEvent(io.temporal.api.nexus.v1.Link nexusLink) {
    Link.Builder link = Link.newBuilder();
    try {
      URI uri = new URI(nexusLink.getUrl());

      if (!uri.getScheme().equals("temporal")) {
        log.error("Failed to parse Nexus link URL: invalid scheme: {}", uri.getScheme());
        return null;
      }

      StringTokenizer st = new StringTokenizer(uri.getRawPath(), "/");
      if (!st.nextToken().equals("namespaces")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String namespace = URLDecoder.decode(st.nextToken(), StandardCharsets.UTF_8.toString());
      if (!st.nextToken().equals("workflows")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }
      String workflowID = URLDecoder.decode(st.nextToken(), StandardCharsets.UTF_8.toString());
      String runID = URLDecoder.decode(st.nextToken(), StandardCharsets.UTF_8.toString());
      if (!st.hasMoreTokens() || !st.nextToken().equals("history")) {
        log.error("Failed to parse Nexus link URL: invalid path: {}", uri.getRawPath());
        return null;
      }

      Link.WorkflowEvent.Builder we =
          Link.WorkflowEvent.newBuilder()
              .setNamespace(namespace)
              .setWorkflowId(workflowID)
              .setRunId(runID);

      if (uri.getQuery() != null) {
        Link.WorkflowEvent.EventReference.Builder eventRef =
            Link.WorkflowEvent.EventReference.newBuilder();
        String query = URLDecoder.decode(uri.getQuery(), StandardCharsets.UTF_8.toString());
        st = new StringTokenizer(query, "&");
        while (st.hasMoreTokens()) {
          String[] param = st.nextToken().split("=");
          switch (param[0]) {
            case "eventID":
              eventRef.setEventId(Long.parseLong(param[1]));
              continue;
            case "eventType":
              // Have to handle the SCREAMING_CASE enum or the traditional temporal PascalCase enum to EventType
              if (param[1].startsWith(EVENT_TYPE_PREFIX)) {
                eventRef.setEventType(EventType.valueOf(param[1]));
              } else {
                eventRef.setEventType(
                    EventType.valueOf(simplifiedToUniqueName(param[1], EVENT_TYPE_PREFIX)));
              }
          }
        }
        we.setEventRef(eventRef);
        link.setWorkflowEvent(we);
      }
    } catch (Exception e) {
      // Swallow un-parsable links since they are not critical to processing
      log.error("Failed to parse Nexus link URL", e);
      return null;
    }
    return link.build();
  }
}
