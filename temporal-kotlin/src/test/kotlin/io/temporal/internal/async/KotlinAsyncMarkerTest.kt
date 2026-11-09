package io.temporal.internal.async

import io.temporal.internal.async.spi.MethodReferenceDisassemblyService
import io.temporal.internal.sync.AsyncInternal
import io.temporal.workflow.Functions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinAsyncMarkerTest {
  private interface Stub {
    fun zero(): Int
    fun execute(value: Int): Int
    fun fire()
    fun fireWith(value: Int)
  }

  private class MarkedStub : Stub, AsyncInternal.AsyncMarker {
    override fun zero() = 0
    override fun execute(value: Int) = value
    override fun fire() {}
    override fun fireWith(value: Int) {}
  }

  private fun <R> asFunc(function: Functions.Func<R>): Functions.Func<R> = function

  private fun <A, R> asFunc1(function: Functions.Func1<A, R>): Functions.Func1<A, R> = function

  private fun asProc(procedure: Functions.Proc): Functions.Proc = procedure

  private fun <A> asProc1(procedure: Functions.Proc1<A>): Functions.Proc1<A> = procedure

  @Test
  fun methodReferencesToMarkedStubsAreAsync() {
    val stub: Stub = MarkedStub()

    assertTrue(AsyncInternal.isAsync(stub::zero))
    assertTrue(AsyncInternal.isAsync(asFunc(stub::zero)))
    assertTrue(AsyncInternal.isAsync(asFunc1(stub::execute)))
    assertTrue(AsyncInternal.isAsync(asProc(stub::fire)))
    assertTrue(AsyncInternal.isAsync(asProc1(stub::fireWith)))
  }

  @Test
  fun lambdasCapturingMarkedStubsAreNotAsync() {
    val stub: Stub = MarkedStub()

    assertFalse(AsyncInternal.isAsync(asFunc { stub.zero() }))
    assertFalse(AsyncInternal.isAsync(asFunc1<Int, Int> { value -> stub.execute(value) }))
    assertFalse(AsyncInternal.isAsync(asProc { stub.fire() }))
    assertFalse(AsyncInternal.isAsync(asProc1<Int> { value -> stub.fireWith(value) }))
  }

  @Test
  fun kotlinLambdasDoNotRequireKotlinDisassemblyService() {
    val kotlinService = MethodReferenceDisassembler.services.remove(MethodReferenceDisassemblyService.KOTLIN)
    try {
      val stub: Stub = MarkedStub()
      assertFalse(AsyncInternal.isAsync(asFunc { stub.zero() }))
      assertFalse(AsyncInternal.isAsync(asFunc1<Int, Int> { value -> stub.execute(value) }))
      assertFalse(AsyncInternal.isAsync(asProc { stub.fire() }))
      assertFalse(AsyncInternal.isAsync(asProc1<Int> { value -> stub.fireWith(value) }))
    } finally {
      if (kotlinService != null) {
        MethodReferenceDisassembler.services[MethodReferenceDisassemblyService.KOTLIN] = kotlinService
      }
    }
  }
}
