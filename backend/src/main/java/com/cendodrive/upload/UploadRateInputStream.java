package com.cendodrive.upload;

import java.io.*;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Bounds file acceptance, not TCP ingress (multipart may already be buffered).
 * Each file has its own budget; callers serialize chunks under the session DB lock.
 */
public final class UploadRateInputStream extends FilterInputStream {
  public static final long BYTES_PER_SECOND = 128 * 1024;
  private final LongSupplier clock;
  private final Sleeper sleeper;
  private long started;
  private boolean reading;
  private long bytes;

  @FunctionalInterface interface Sleeper { void sleep(long nanos) throws InterruptedException; }

  public UploadRateInputStream(InputStream input) {
    this(input, System::nanoTime, nanos -> java.util.concurrent.TimeUnit.NANOSECONDS.sleep(nanos));
  }

  UploadRateInputStream(InputStream input, LongSupplier clock, Sleeper sleeper) {
    super(input);
    this.clock = clock;
    this.sleeper = sleeper;
  }

  private void start() {
    if (!reading) {
      started = clock.getAsLong();
      reading = true;
    }
  }

  private void pace(int count) throws IOException {
    if (count <= 0) return;
    bytes += count;
    long target = (bytes / BYTES_PER_SECOND) * 1_000_000_000L
        + (bytes % BYTES_PER_SECOND) * 1_000_000_000L / BYTES_PER_SECOND;
    try {
      long remaining;
      while ((remaining = target - (clock.getAsLong() - started)) > 0) sleeper.sleep(remaining);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("Upload throttling interrupted");
    }
  }

  @Override public int read() throws IOException {
    start();
    int value = in.read();
    if (value >= 0) pace(1);
    return value;
  }
  @Override public int read(byte[] buffer, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, buffer.length);
    if (length == 0) return 0;
    start();
    int count = in.read(buffer, offset, Math.min(length, 8192));
    pace(count);
    return count;
  }
  @Override public long skip(long count) throws IOException {
    if (count <= 0) return 0;
    byte[] buffer = new byte[(int)Math.min(count, 8192)];
    long skipped = 0;
    while (skipped < count) {
      int read = read(buffer, 0, (int)Math.min(buffer.length, count - skipped));
      if (read < 0) break;
      skipped += read;
    }
    return skipped;
  }
  @Override public boolean markSupported() { return false; }
  @Override public synchronized void mark(int limit) { }
  @Override public synchronized void reset() throws IOException { throw new IOException("Mark/reset unsupported"); }
}
