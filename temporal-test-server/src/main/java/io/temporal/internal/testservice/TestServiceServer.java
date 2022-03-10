/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package io.temporal.internal.testservice;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class TestServiceServer {

  public static void main(String[] args) throws IOException, RuntimeException {
    if (args.length != 1 && args.length != 2) {
      System.err.println("Usage: <command> <port> [port callback URL]");
    }
    Integer port = Integer.parseInt(args[0]);

    TestWorkflowService server = TestWorkflowService.createServerOnly(port);
    if (args.length >= 2) {
      String callbackURL = args[1];
      Integer actualPort = server.getPort();
      URL url = new URL(callbackURL + "?port=" + actualPort);
      HttpURLConnection huc = (HttpURLConnection) url.openConnection();
      huc.setRequestMethod("GET");
      if (huc.getResponseCode() != 200) {
        throw new RuntimeException(
            "Failed to report listening port to provided callback URL: " + callbackURL);
      }
    }
  }
}
