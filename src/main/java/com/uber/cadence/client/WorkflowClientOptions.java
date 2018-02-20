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
package com.uber.cadence.client;

import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.converter.JsonDataConverter;

/**
 * Options for WorkflowClient configuration.
 */
public final class WorkflowClientOptions {

    public static final class Builder {

        private DataConverter dataConverter = JsonDataConverter.getInstance();

        /**
         * Used to override default (JSON) data converter implementation.
         *
         * @param dataConverter data converter to serialize and deserialize arguments and return values.
         */
        public Builder setDataConverter(DataConverter dataConverter) {
            if (dataConverter == null) {
                throw new IllegalArgumentException("null");
            }
            this.dataConverter = dataConverter;
            return this;
        }

        public WorkflowClientOptions build() {
            return new WorkflowClientOptions(dataConverter);
        }
    }


    private final DataConverter dataConverter;

    private WorkflowClientOptions(DataConverter dataConverter) {
        this.dataConverter = dataConverter;
    }

    public DataConverter getDataConverter() {
        return dataConverter;
    }
}
