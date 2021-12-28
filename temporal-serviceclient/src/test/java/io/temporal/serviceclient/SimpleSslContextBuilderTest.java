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

package io.temporal.serviceclient;

import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

public class SimpleSslContextBuilderTest {
  @Test
  public void ableToLoadPKCS12Key() throws IOException {
    try (InputStream in = SimpleSslContextBuilderTest.class.getResourceAsStream("pkcs12-key.pfx")) {
      SimpleSslContextBuilder builder = SimpleSslContextBuilder.forPKCS12(in);
      builder.build();
    }
  }

  @Test
  public void ableToLoadPKCS8Key() throws IOException {
    try (InputStream pkIn = SimpleSslContextBuilderTest.class.getResourceAsStream("pkcs8-pk.pem");
        InputStream crtIn =
            SimpleSslContextBuilderTest.class.getResourceAsStream("pkcs8-crt-chain.pem")) {
      SimpleSslContextBuilder builder = SimpleSslContextBuilder.forPKCS8(crtIn, pkIn);
      builder.build();
    }
  }
}
