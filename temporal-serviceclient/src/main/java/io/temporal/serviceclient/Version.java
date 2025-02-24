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

package io.temporal.serviceclient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * below are the metadata which will be embedded as part of headers in every rpc call made by this
 * client to Temporal server.
 *
 * <p>Update to the metadata below is typically done by the Temporal team as part of a major feature
 * or behavior change
 */
public class Version {

  /**
   * Library Version is a semver that represents the version of this Temporal client library. This
   * represent API changes visible to Temporal client side library consumers. I.e. developers that
   * are writing workflows. So every time we change API that can affect them we have to change this
   * number. Format: MAJOR.MINOR.PATCH
   */
  public static final String LIBRARY_VERSION;

  /**
   * The named used to represent this SDK as client identities as well as worker task completions.
   */
  public static final String SDK_NAME = "temporal-java";

  /**
   * Supported server versions defines a semver range of server versions that client is compatible
   * with.
   */
  public static final String SUPPORTED_SERVER_VERSIONS = ">=0.31.0 <2.0.0";

  static {
    // Load version from version.properties generated by Gradle into build/resources/main directory.
    Properties prop = new Properties();
    InputStream in = Version.class.getResourceAsStream("/io/temporal/version.properties");
    if (in == null) {
      LIBRARY_VERSION = "UNKNOWN";
    } else {
      String version = null;
      try {
        try {
          prop.load(in);
          version = prop.getProperty("temporal-client-version");
        } finally {
          in.close();
        }
      } catch (IOException e) {
        if (version == null) {
          version = "UNKNOWN";
        }
      }
      LIBRARY_VERSION = version;
    }
  }
}
