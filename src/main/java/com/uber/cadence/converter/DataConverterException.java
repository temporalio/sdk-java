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
package com.uber.cadence.converter;


import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * @author fateev
 * @see DataConverter
 */
@SuppressWarnings("serial")
public class DataConverterException extends RuntimeException {

    private Class<?>[] valueTypes;

    private String content;

    public DataConverterException(byte[] content, Class<?>[] valueTypes, Throwable cause) {
        super(cause);
        this.valueTypes = valueTypes;
        setContent(content);
    }

    public DataConverterException(byte[] content, Throwable cause) {
        super(cause);
        setContent(content);
    }

    public DataConverterException(Exception e) {
        super(e);
    }

    private void setContent(byte[] content) {
        if (content != null) {
            // Limit size of the string.
            int maxIndex = Math.min(content.length, 255);
            this.content = new String(content, 0, maxIndex, StandardCharsets.UTF_8);
        }
    }

    @Override
    public String getMessage() {
        if (content == null && valueTypes == null) {
            return super.getMessage();
        }
        return super.getMessage() + " when parsing:\"" + content + "\" into following types: "
                + Arrays.toString(valueTypes);
    }
}
