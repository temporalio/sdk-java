package io.temporal.common.converter;

import static org.junit.Assert.*;

import com.google.common.base.Objects;
import com.google.common.reflect.TypeToken;
import io.temporal.api.common.v1.Payloads;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class EncodedValuesTest {

  public static class Pair {
    public int i;
    public String s;

    public Pair(int i, String s) {
      this.i = i;
      this.s = s;
    }

    public Pair() {}

    public int getI() {
      return i;
    }

    public String getS() {
      return s;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Pair pair = (Pair) o;
      return i == pair.i && Objects.equal(s, pair.s);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(i, s);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGenericParameter() {
    ArrayList<Pair> list = new ArrayList<>();
    list.add(new Pair(10, "foo"));
    list.add(new Pair(12, "bar"));
    EncodedValues v = new EncodedValues(list);
    DataConverter converter = GlobalDataConverter.get();
    v.setDataConverter(converter);
    Optional<Payloads> payloads = v.toPayloads();
    Values v2 = new EncodedValues(payloads, converter);
    TypeToken<List<Pair>> typeToken = new TypeToken<List<Pair>>() {};
    List<Pair> result = v2.get(List.class, typeToken.getType());
    assertEquals(list, result);
  }

  @Test
  public void testEmptyParameter() {
    EncodedValues v = new EncodedValues(null);
    Optional<Payloads> payloads = v.toPayloads();
    assertFalse(payloads.isPresent());
  }
}
