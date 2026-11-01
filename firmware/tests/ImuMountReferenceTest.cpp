#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include "../boards/175/MotoNav/src/diagnostics/ImuMountReference.h"

using namespace ImuMount;

static bool close(float actual, float expected, float tolerance = 0.001f) {
  return std::isfinite(actual) && std::fabs(actual - expected) <= tolerance;
}

static void stableSample(Collector &collector, uint32_t now) {
  // Measured board bias is intentionally retained. It is not an absolute motion gate.
  collector.add(now, -0.461f, -0.597f, 9.037f, -6.855f, 0.732f, 0.128f);
}

static void testRelativeTilt() {
  assert(close(relativeTiltDeg(0, 0, 9.8f, 0, 0, 9.8f), 0));
  assert(close(relativeTiltDeg(0, 0, 9.8f, 9.8f, 0, 0), 90));
  assert(close(relativeTiltDeg(0, 0, 9.8f, 0, 0, -9.8f), 180));
  // Installation may start on any axis; inverted mounting must not collapse to zero.
  assert(close(relativeTiltDeg(9.8f, 0, 0, 0, 0, 9.8f), 90));
  assert(close(relativeTiltDeg(3, 4, 8, 6, 8, 16), 0, 0.03f));
  assert(close(relativeTiltDeg(3, 4, 8, -3, -4, -8), 180, 0.03f));
  assert(close(relativeTiltDeg(0, 0, 9.8f, 0, 0, 200), 0));
  assert(std::isnan(relativeTiltDeg(0, 0, 0, 0, 0, 9.8f)));
  assert(std::isnan(relativeTiltDeg(0, 0, 9.8f, 0, 0, 0)));
  assert(std::isnan(relativeTiltDeg(0, 0, 9.8f, 0, 0, 0.000001f)));
  assert(std::isnan(relativeTiltDeg(0, 0, 9.8f, 0, 0, 200.01f)));
  assert(std::isnan(relativeTiltDeg(NAN, 0, 9.8f, 0, 0, 9.8f)));
  assert(std::isnan(relativeTiltDeg(0, 0, 9.8f, INFINITY, 0, 0)));
}

static void testReferencePersistence() {
  Reference original = makeReference(1, -0.461f, -0.597f, 9.037f);
  assert(referenceValid(original));
  // Exercise the exact persisted bytes, not a reconstruction from axis keys.
  unsigned char bytes[sizeof(Reference)];
  std::memcpy(bytes, &original, sizeof(bytes));
  Reference restored = {};
  std::memcpy(&restored, bytes, sizeof(restored));
  assert(referenceValid(restored));
  assert(restored.revision == 1 && restored.ax == original.ax);
  bytes[offsetof(Reference, ax)] ^= 1;
  std::memcpy(&restored, bytes, sizeof(restored));
  assert(!referenceValid(restored));
  Reference invalid = original;
  invalid.schema++;
  invalid.checksum = checksum(invalid);
  assert(!referenceValid(invalid));
  invalid = original;
  invalid.magic++;
  invalid.checksum = checksum(invalid);
  assert(!referenceValid(invalid));
  assert(!referenceValid(makeReference(0, 0, 0, 9.8f)));
  assert(!referenceValid(makeReference(1, 0, 0, 0)));
  assert(!referenceValid(makeReference(1, NAN, 0, 9.8f)));
  assert(gravityValid(0, 0, 7.5f) && gravityValid(0, 0, 12.5f));
  assert(!gravityValid(0, 0, 7.49f) && !gravityValid(0, 0, 12.51f));
}

static void testSuccessfulCaptureAndDuplicate() {
  Collector collector;
  collector.start(100, true);
  stableSample(collector, 100);
  stableSample(collector, 100);
  assert(collector.count == 1);
  for (uint32_t elapsed = 20; elapsed < CAPTURE_MS; elapsed += 20) {
    stableSample(collector, 100 + elapsed);
    assert(collector.state == COLLECTING);
  }
  stableSample(collector, 100 + CAPTURE_MS);
  assert(collector.state == READY && collector.reason == NONE);
  assert(collector.count == 251);
  assert(close(collector.accel[0].mean, -0.461f));
  assert(close(collector.gyro[0].mean, -6.855f));
  collector.fail(SAVE); // Adapter cannot publish READY when its NVS write fails.
  assert(collector.state == FAILED && collector.reason == SAVE);
}

static void testMinimumCountAndTimeout() {
  Collector collector;
  collector.start(0, true);
  // 50 ms is within the permitted native gap, but too sparse to finish at 5 seconds.
  for (uint32_t elapsed = 0; elapsed <= CAPTURE_MS; elapsed += 50) stableSample(collector, elapsed);
  assert(collector.state == COLLECTING && collector.count == 101);
  for (uint32_t elapsed = CAPTURE_MS + 50; elapsed < TIMEOUT_MS; elapsed += 50) stableSample(collector, elapsed);
  assert(collector.state == COLLECTING && collector.count < MIN_SAMPLES);
  collector.tick(TIMEOUT_MS, true);
  assert(collector.state == FAILED && collector.reason == TIMEOUT);
}

static void testMovementRejection() {
  Collector collector;
  collector.start(0, true);
  stableSample(collector, 0);
  collector.add(16, 0.139f, -0.597f, 9.037f, -6.855f, 0.732f, 0.128f);
  assert(collector.state == FAILED && collector.reason == MOVED); // acceleration range
  collector.start(0, true);
  stableSample(collector, 0);
  collector.add(16, -0.261f, -0.597f, 9.037f, -6.855f, 0.732f, 0.128f);
  assert(collector.state == FAILED && collector.reason == MOVED); // variance below range limit
  collector.start(0, true);
  stableSample(collector, 0);
  collector.add(16, -0.461f, -0.597f, 9.037f, -3.855f, 0.732f, 0.128f);
  assert(collector.state == FAILED && collector.reason == MOVED); // gyro change
  collector.start(0, true);
  collector.add(0, 0, 0, 2, -7, 0, 0);
  assert(collector.state == FAILED && collector.reason == MOVED); // gravity magnitude
}

static void testSensorHealthAndGap() {
  Collector collector;
  collector.start(0, false);
  assert(collector.state == FAILED && collector.reason == SENSOR);
  collector.start(0, true);
  stableSample(collector, 0);
  collector.tick(16, false);
  assert(collector.state == FAILED && collector.reason == SENSOR);
  collector.start(0, true);
  collector.add(0, 0, 0, 9.8f, -7, 0, 0, true, true);
  assert(collector.state == FAILED && collector.reason == SENSOR);
  collector.start(0, true);
  collector.add(0, 0, 0, 9.8f, NAN, 0, 0);
  assert(collector.state == FAILED && collector.reason == SENSOR);
  collector.start(0, true);
  collector.add(0, NAN, 0, 9.8f, -7, 0, 0);
  assert(collector.state == FAILED && collector.reason == SENSOR);
  collector.start(0, true);
  collector.tick(MAX_GAP_MS, true);
  assert(collector.state == COLLECTING);
  collector.tick(MAX_GAP_MS + 1, true);
  assert(collector.state == FAILED && collector.reason == GAP);
  collector.start(100, true);
  stableSample(collector, 100);
  stableSample(collector, 100 + MAX_GAP_MS);
  assert(collector.state == COLLECTING && collector.count == 2);
  stableSample(collector, 100 + 2 * MAX_GAP_MS + 1);
  assert(collector.state == FAILED && collector.reason == GAP);
}

static void testClockRolloverAndFailedReplacement() {
  const Reference oldReference = makeReference(12, 0, 0, -9.8f);
  Reference activeReference = oldReference;
  Collector collector;
  const uint32_t start = UINT32_MAX - 1000;
  collector.start(start, true);
  for (uint32_t elapsed = 0; elapsed <= CAPTURE_MS; elapsed += 20) stableSample(collector, start + elapsed);
  assert(collector.state == READY && collector.count == 251);
  collector.start(start, true);
  stableSample(collector, start);
  collector.tick(start + MAX_GAP_MS + 1, true);
  assert(collector.state == FAILED && collector.reason == GAP);
  // Only successful adapter persistence replaces activeReference, never Collector::start/fail.
  assert(std::memcmp(&activeReference, &oldReference, sizeof(oldReference)) == 0);
  assert(referenceValid(activeReference) && activeReference.revision == 12);
}

int main() {
  testRelativeTilt();
  testReferencePersistence();
  testSuccessfulCaptureAndDuplicate();
  testMinimumCountAndTimeout();
  testMovementRejection();
  testSensorHealthAndGap();
  testClockRolloverAndFailedReplacement();
  puts("PASS: mount tilt, reference checksum, native capture, duplicates, motion/health/gaps, timeout, rollover");
}
