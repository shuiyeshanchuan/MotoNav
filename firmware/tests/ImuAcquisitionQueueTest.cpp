#include <atomic>
#include <cassert>
#include <cstdio>
#include <mutex>
#include <thread>
#include <vector>
#include "../boards/175/MotoNav/src/diagnostics/ImuAcquisitionQueue.h"
#include "../boards/175/MotoNav/src/diagnostics/ContinuousImuRecordCore.h"

static ImuAcquisition::Event eventAt(uint32_t at, bool valid = true) {
  ImuAcquisition::Event event;
  event.timestamp = at;
  event.valid = valid;
  event.ax = float(at); event.az = 9.8f; event.gx = -7.0f;
  event.rawAccel[0] = int16_t(at);
  return event;
}

static void control(ContinuousImu::Recorder &recorder, uint8_t op, uint32_t session, uint32_t now) {
  uint8_t bytes[ContinuousImu::CONTROL_SIZE] = {0xD7, 0x43, 1, op};
  ContinuousImu::write32(bytes + 4, session);
  ContinuousImu::write32(bytes + 8, 1);
  ContinuousImu::write16(bytes + 16, ContinuousImu::crc16(bytes, 16));
  assert(recorder.control(bytes, sizeof(bytes), now));
}

template <size_t Capacity> static void drain(ImuAcquisition::Queue<Capacity> &queue,
    ContinuousImu::Recorder &recorder, uint32_t &seenErrors, uint32_t &seenDrops) {
  const auto pending = queue.snapshot();
  ImuAcquisition::Event event;
  for (size_t i = 0; i < pending.count; ++i) {
    assert(queue.pop(event));
    recorder.readError(event.readErrors - seenErrors);
    seenErrors = event.readErrors;
    if (event.valid) recorder.sample({event.timestamp, event.ax, event.ay, event.az,
        event.gx, event.gy, event.gz, event.temperature});
  }
  recorder.readError(pending.readErrors - seenErrors);
  seenErrors = pending.readErrors;
  recorder.acquisitionOverflow(pending.dropped - seenDrops);
  seenDrops = pending.dropped;
}

static void testDisplayStallsDoNotChangeAcquisitionTimes() {
  ImuAcquisition::Queue<256> queue;
  ContinuousImu::Sample storage[100] = {};
  ContinuousImu::Recorder recorder;
  recorder.initialize(storage, 100, 1);
  recorder.setSensorHealthy(true);
  control(recorder, 1, 100, 0);
  uint32_t errors = 0, drops = 0;
  // The sampler keeps reading real values while the display loop stalls 300 ms.
  for (uint32_t at = 0; at <= 2000; at += 10) {
    assert(queue.push(eventAt(at)));
    if (at % 300 == 290) drain(queue, recorder, errors, drops);
  }
  drain(queue, recorder, errors, drops);
  control(recorder, 2, 100, 2001);
  assert(recorder.nextSeq() == 51);
  assert(recorder.missedSlots() == 0 && recorder.overflow() == 0);
  for (uint32_t i = 0; i < recorder.nextSeq(); ++i) {
    assert(storage[i].timestamp == i * 40);
    assert(storage[i].ax == float(i * 40));
    assert(storage[i].gx == -7.0f); // Raw bias is retained, never silently calibrated.
  }
}

static void testBoundedLossAndSessionBoundaries() {
  ImuAcquisition::Queue<4> queue;
  ContinuousImu::Sample storage[20] = {};
  ContinuousImu::Recorder recorder;
  recorder.initialize(storage, 20, 1);
  recorder.setSensorHealthy(true);
  uint32_t errors = 0, drops = 0;
  queue.push(eventAt(0, false));
  drain(queue, recorder, errors, drops); // Pre-START error cannot leak into session.
  control(recorder, 1, 100, 100);
  for (uint32_t at = 100; at <= 220; at += 40) assert(queue.push(eventAt(at)));
  assert(!queue.push(eventAt(260)));
  assert(!queue.push(eventAt(270, false)));
  drain(queue, recorder, errors, drops);
  assert(recorder.nextSeq() == 4 && recorder.overflow() == 2 && recorder.readErrors() == 1);
  assert(queue.push(eventAt(300)));
  drain(queue, recorder, errors, drops);
  assert(recorder.missedSlots() == 1); // The real missing time is still exposed.
  control(recorder, 2, 100, 301);
  assert(queue.push(eventAt(320, false)));
  drain(queue, recorder, errors, drops);
  assert(recorder.readErrors() == 1); // Post-STOP error is outside the session.
  control(recorder, 1, 101, 400);
  assert(recorder.overflow() == 0 && recorder.readErrors() == 0);
  queue.push(eventAt(400));
  drain(queue, recorder, errors, drops);
  assert(recorder.nextSeq() == 1 && storage[0].timestamp == 400);
}

static void testSnapshotPrefixAndWrap() {
  ImuAcquisition::Queue<3> queue;
  queue.push(eventAt(UINT32_MAX - 9));
  queue.push(eventAt(0));
  const auto before = queue.snapshot();
  queue.push(eventAt(10)); // A concurrent new event belongs to the next prefix.
  ImuAcquisition::Event event;
  assert(before.count == 2);
  assert(queue.pop(event) && event.timestamp == UINT32_MAX - 9);
  assert(queue.pop(event) && event.timestamp == 0);
  assert(queue.snapshot().count == 1);
  assert(queue.pop(event) && event.timestamp == 10);
  assert(!queue.pop(event));
}

static void testConcurrentProducerConsumer() {
  ImuAcquisition::Queue<16> queue;
  std::mutex mutex;
  std::atomic<bool> done{false};
  constexpr uint32_t total = 100000;
  std::vector<uint32_t> received;
  std::thread producer([&] {
    for (uint32_t i = 1; i <= total; ++i) {
      const std::lock_guard<std::mutex> lock(mutex);
      queue.push(eventAt(i, i % 7 != 0));
    }
    done.store(true);
  });
  for (;;) {
    ImuAcquisition::Event event;
    bool have;
    {
      const std::lock_guard<std::mutex> lock(mutex);
      have = queue.pop(event);
      if (!have && done.load()) break;
    }
    if (have) {
      assert(event.ax == float(event.timestamp));
      assert(event.rawAccel[0] == int16_t(event.timestamp));
      assert(event.readErrors == event.timestamp / 7);
      if (!received.empty()) assert(event.timestamp > received.back());
      received.push_back(event.timestamp);
    } else std::this_thread::yield();
  }
  producer.join();
  assert(received.size() + queue.snapshot().dropped == total);
  assert(queue.snapshot().readErrors == total / 7);
  assert(queue.snapshot().count == 0);
}

int main() {
  testDisplayStallsDoNotChangeAcquisitionTimes();
  testBoundedLossAndSessionBoundaries();
  testSnapshotPrefixAndWrap();
  testConcurrentProducerConsumer();
  std::puts("PASS: IMU acquisition queue, display stalls, real timestamps, bounded loss, errors, sessions and concurrency");
}
