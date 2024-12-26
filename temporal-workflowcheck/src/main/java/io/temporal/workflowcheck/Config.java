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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Configuration for workflow check. See README for configuration format. */
public class Config {
  /** Load the default set of config properties. */
  public static Properties defaultProperties() throws IOException {
    Properties props = new Properties();
    try (InputStream is = Config.class.getResourceAsStream("workflowcheck.properties")) {
      props.load(is);
    }
    return props;
  }

  /**
   * Create a new configuration from the given set of properties. Later properties with the same key
   * overwrite previous ones, but more specific properties apply before less specific ones.
   */
  public static Config fromProperties(Properties... props) {
    return new Config(new DescriptorMatcher("invalid", props));
  }

  final DescriptorMatcher invalidMembers;

  private Config(DescriptorMatcher invalidMembers) {
    this.invalidMembers = invalidMembers;
  }
}
