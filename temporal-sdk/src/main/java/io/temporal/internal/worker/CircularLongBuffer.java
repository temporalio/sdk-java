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
