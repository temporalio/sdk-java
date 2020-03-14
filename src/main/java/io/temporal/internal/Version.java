/*
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

package io.temporal.internal;

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
   * represent API changes visibile to Temporal client side library consumers. I.e. developers that
   * are writing workflows. So every time we change API that can affect them we have to change this
   * number. Format: MAJOR.MINOR.PATCH
   */
  public static final String LIBRARY_VERSION;

  /**
   * Feature Version is a semver that represents the feature set of this Temporal client library
   * support. This can be used for client capibility check, on Temporal server, for backward
   * compatibility Format: MAJOR.MINOR.PATCH
   */
  public static final String FEATURE_VERSION = "1.5.0";

  static {
    // Load version from version.properties generated by Gradle into build/resources/main directory.
    Properties prop = new Properties();
    InputStream in = Version.class.getResourceAsStream("/io.temporal/version.properties");
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
