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

package io.temporal.internal.worker;

class CircularLongBuffer {

  private final long[] values_;

  public CircularLongBuffer(int size) {
    values_ = new long[size];
  }

  public CircularLongBuffer(long[] values) {
    values_ = values;
  }

  public void set(long i, long value) {
    values_[getArrayOffset(i)] = value;
  }

  public long get(long i) {
    return values_[getArrayOffset(i)];
  }

  public int size() {
    return values_.length;
  }

  public CircularLongBuffer copy(long index1, int length) {
    if (length == 0) {
      return new CircularLongBuffer(0);
    }
    int i1 = getArrayOffset(index1);
    int i2 = getArrayOffset(index1 + Math.min(length, values_.length));
    long[] result = new long[length];
    if (i1 < i2) {
      int l = i2 - i1;
      System.arraycopy(values_, i1, result, 0, l);
    } else {
      int tailLength = values_.length - i1;
      System.arraycopy(values_, i1, result, 0, tailLength);
      System.arraycopy(values_, 0, result, tailLength, i2);
    }
    return new CircularLongBuffer(result);
  }

  private int getArrayOffset(long index) {
    if (values_.length == 0) {
      throw new IllegalStateException("zero data size");
    }
    int result = (int) (index % values_.length);
    if (result < 0) {
      result = values_.length + result;
    }
    return result;
  }
}
