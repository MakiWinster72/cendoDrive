package com.cendodrive.upload;

import java.io.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadRateInputStreamTest {
  @Test void membershipPolicyBypassesLimiterOnlyForSvip() {
    var user = new com.cendodrive.user.User("policy", "hash", "Policy");
    var raw = new ByteArrayInputStream(new byte[1]);
    assertInstanceOf(UploadRateInputStream.class, UploadRateInputStream.forUser(raw, user));
    user.activateSimulatedMembership();
    assertSame(raw, UploadRateInputStream.forUser(raw, user));
  }
  @Test void bulkReadsCannotBypass128KiBPerSecond() throws Exception {
    AtomicLong time = new AtomicLong();
    byte[] data = new byte[256 * 1024];
    try (var input = new UploadRateInputStream(new ByteArrayInputStream(data), time::get, time::addAndGet)) {
      // Waiting before a file begins must not accumulate credit.
      time.addAndGet(10_000_000_000L);
      assertArrayEquals(data, input.readAllBytes());
      assertEquals(12_000_000_000L, time.get());
      assertEquals(-1, input.read());
      assertEquals(12_000_000_000L, time.get());
    }
  }
  @Test void individualReadsAndSkipArePaced() throws Exception {
    AtomicLong time = new AtomicLong();
    try (var input = new UploadRateInputStream(new ByteArrayInputStream(new byte[131072]), time::get, time::addAndGet)) {
      assertEquals(0, input.read());
      assertEquals(131071, input.skip(131071));
      assertEquals(1_000_000_000L, time.get());
      assertFalse(input.markSupported());
      assertThrows(IOException.class, input::reset);
    }
  }
  @Test void filesHaveIndependentBudgets() throws Exception {
    AtomicLong a = new AtomicLong(), b = new AtomicLong();
    try (var first = new UploadRateInputStream(new ByteArrayInputStream(new byte[8192]), a::get, a::addAndGet);
         var second = new UploadRateInputStream(new ByteArrayInputStream(new byte[8192]), b::get, b::addAndGet)) {
      first.readAllBytes(); second.readAllBytes();
      assertEquals(62_500_000L, a.get()); assertEquals(a.get(), b.get());
    }
  }
  @Test void interruptIsPropagatedAndFlagPreserved() {
    var input = new UploadRateInputStream(new ByteArrayInputStream(new byte[1]), () -> 0L,
        nanos -> { throw new InterruptedException(); });
    try {
      assertThrows(InterruptedIOException.class, input::read);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally { Thread.interrupted(); }
  }
  @Test void realClockProbe() throws Exception {
    long start = System.nanoTime();
    try (var input = new UploadRateInputStream(new ByteArrayInputStream(new byte[16384]))) {
      assertEquals(16384, input.readAllBytes().length);
    }
    assertTrue(System.nanoTime() - start >= 125_000_000L);
  }
}
