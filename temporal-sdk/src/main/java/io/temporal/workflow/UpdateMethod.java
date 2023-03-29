/*
 * Copyright (C) 2023 Temporal Technologies, Inc. All Rights Reserved.
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

package io.temporal.workflow;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that the method is a update handler method. A update method gets executed when a
 * workflow receives a update after the validator is called.
 *
 * <p>This annotation applies only to workflow interface methods.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface UpdateMethod {
  /**
   * Name of the update handler. Default is method name.
   *
   * <p>Be careful about names that contain special characters. These names can be used as metric
   * tags. And systems like prometheus ignore metrics which have tags with unsupported characters.
   */
  String name() default "";

  /**
   * Validator to run on update input. If the validator fails this update is not recorded in histroy. Default validator is a no-op.
   */
  Class<? extends Validator<?>> validator() default Validator.None.class;
}


