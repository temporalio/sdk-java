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

package io.temporal.internal.common.converter;

import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.SearchAttributes;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SearchAttributesUtil {
  private static final SearchAttributesPayloadConverter converter =
      SearchAttributesPayloadConverter.INSTANCE;
  private static final Logger log = LoggerFactory.getLogger(SearchAttributesUtil.class);

  public static SearchAttributes encode(Map<String, Object> searchAttributes) {
    return SearchAttributes.newBuilder()
        .putAllIndexedFields(objectToPayloadMap(searchAttributes))
        .build();
  }

  public static Map<String, Object> decode(SearchAttributes searchAttributes) {
    if (searchAttributes == null || searchAttributes.getIndexedFieldsCount() == 0) {
      return null;
    }
    return payloadToObjectMap(searchAttributes.getIndexedFieldsMap());
  }

  public static Map<String, Payload> objectToPayloadMap(Map<String, Object> map) {
    if (map == null || map.isEmpty()) {
      return null;
    }
    Map<String, Payload> payloadMap = new HashMap<>(map.size());
    map.forEach((key, value) -> payloadMap.put(key, converter.toData(value).get()));
    return payloadMap;
  }

  public static Map<String, Object> payloadToObjectMap(Map<String, Payload> serializedMap) {
    if (serializedMap == null || serializedMap.isEmpty()) {
      return null;
    }
    Map<String, Object> deserializedMap = new HashMap<>();
    for (Map.Entry<String, Payload> attribute : serializedMap.entrySet()) {
      String key = attribute.getKey();
      Object data = converter.fromData(attribute.getValue());
      if (data == null) {
        log.error("Error parsing Search Attribute: {}.", key);
      } else {
        deserializedMap.put(key, data);
      }
    }
    return deserializedMap;
  }

  public enum SearchAttributeType {
    Unspecified,
    String,
    Keyword,
    Int,
    Double,
    Bool,
    Datetime
  }
}
