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

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

public class SimpleSslContextBuilderTest {
  @Test
  public void ableToLoadPKCS12Key() throws IOException {
    try (InputStream in =
        SimpleSslContextBuilderTest.class.getClassLoader().getResourceAsStream("pkcs12-key.pfx")) {
      Preconditions.checkState(in != null);
      SimpleSslContextBuilder builder = SimpleSslContextBuilder.forPKCS12(in);
      builder.build();
    }
  }

  // to give easier API for configuration to users we allow null inputs
  @Test
  public void nullInputIsAcceptedForPKCS12Key() throws IOException {
    SimpleSslContextBuilder builder = SimpleSslContextBuilder.forPKCS12(null);
    builder.build();
  }

  @Test
  public void ableToLoadPKCS8Key() throws IOException {
    try (InputStream pkIn =
            SimpleSslContextBuilderTest.class.getClassLoader().getResourceAsStream("pkcs8-pk.pem");
        InputStream crtIn =
            SimpleSslContextBuilderTest.class
                .getClassLoader()
                .getResourceAsStream("pkcs8-crt-chain.pem")) {
      Preconditions.checkState(pkIn != null);
      Preconditions.checkState(crtIn != null);

      SimpleSslContextBuilder builder = SimpleSslContextBuilder.forPKCS8(crtIn, pkIn);
      builder.build();
    }
  }

  // to give easier API for configuration to users we allow null inputs
  @Test
  public void nullInputIsAcceptedForPKCS8Key() throws IOException {
    SimpleSslContextBuilder builder = SimpleSslContextBuilder.forPKCS8(null, null);
    builder.build();
  }

  @Test
  public void ableToCreateWithoutKeyOrCerts() throws IOException {
    SimpleSslContextBuilder builder = SimpleSslContextBuilder.noKeyOrCertChain();
    builder.build();
  }
}
