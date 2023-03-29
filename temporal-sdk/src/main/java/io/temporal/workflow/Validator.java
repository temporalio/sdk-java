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

/**
 * Validator are used during update to decided weather to accept or reject an update workflow
 * execution.
 */
@FunctionalInterface
public interface Validator<W> {
    
    void apply(W workflowObject, Object[] args);

    public class None implements Validator<Object> {
        @Override
        public void apply(Object workflowObject, Object[] args) {
        }
    }
}