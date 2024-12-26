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

package io.temporal.workflowcheck;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

public class LoggingCaptureHandler extends Handler {
  private final List<LogRecord> records = new ArrayList<>();

  public LoggingCaptureHandler() {
    setFormatter(new SimpleFormatter());
  }

  @Override
  public synchronized void publish(LogRecord record) {
    records.add(record);
  }

  @Override
  public void flush() {}

  @Override
  public void close() throws SecurityException {}

  public synchronized List<LogRecord> collectRecords() {
    return new ArrayList<>(records);
  }
}
