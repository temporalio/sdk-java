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

package com.uber.cadence.testing;

import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JsonDataConverter;
import com.uber.cadence.serviceclient.IWorkflowService;
import java.util.Objects;

public class TestEnvironmentOptions {

  public static class Builder {

    private DataConverter dataConverter = JsonDataConverter.getInstance();

    private String domain = "unit-test";

    public Builder setDataConverter(DataConverter dataConverter) {
      Objects.requireNonNull(dataConverter);
      this.dataConverter = dataConverter;
      return this;
    }

    public Builder setDomain(String domain) {
      Objects.requireNonNull(domain);
      this.domain = domain;
      return this;
    }

    public Builder setService(IWorkflowService service) {
      return this;
    }

    public TestEnvironmentOptions build() {
      return new TestEnvironmentOptions(dataConverter, domain);
    }
  }

  private final DataConverter dataConverter;

  private final String domain;

  private TestEnvironmentOptions(DataConverter dataConverter, String domain) {
    this.dataConverter = dataConverter;
    this.domain = domain;
  }

  public DataConverter getDataConverter() {
    return dataConverter;
  }

  public String getDomain() {
    return domain;
  }

  @Override
  public String toString() {
    return "TestEnvironmentOptions{"
        + "dataConverter="
        + dataConverter
        + ", domain='"
        + domain
        + '\''
        + '}';
  }
}
