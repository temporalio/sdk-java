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

package io.temporal.spring.boot.autoconfigure;

import io.temporal.common.converter.DataConverter;
import java.util.List;
import javax.annotation.Nullable;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;

class AutoConfigurationUtils {
  @Nullable
  static DataConverter choseDataConverter(
      List<DataConverter> dataConverters, DataConverter mainDataConverter) {
    DataConverter chosenDataConverter = null;
    if (dataConverters.size() == 1) {
      chosenDataConverter = dataConverters.get(0);
    } else if (dataConverters.size() > 1) {
      if (mainDataConverter != null) {
        chosenDataConverter = mainDataConverter;
      } else {
        throw new NoUniqueBeanDefinitionException(
            DataConverter.class,
            dataConverters.size(),
            "Several DataConverter beans found in the Spring context. "
                + "Explicitly name 'mainDataConverter' the one bean "
                + "that should be used by Temporal Spring Boot AutoConfiguration.");
      }
    }
    return chosenDataConverter;
  }
}
