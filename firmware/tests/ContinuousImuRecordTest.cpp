#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
#include "../boards/175/MotoNav/src/diagnostics/ContinuousImuRecordCore.h"

using namespace ContinuousImu;

static constexpr uint32_t BOOT = 0x12345678u;
static constexpr uint32_t SESSION = 0x23456789u;
static constexpr uint8_t START = 1, STOP = 2, ACK = 3, QUERY = 4;

static std::array<uint8_t, 18> command(uint8_t op, uint32_t session,
                                       uint32_t boot, uint32_t arg = 0) {
  std::array<uint8_t, 18> bytes{};
  bytes[0] = 0xD7;
  bytes[1] = 0x43;
  bytes[2] = 1;
  bytes[3] = op;
  write32(bytes.data() + 4, session);
  write32(bytes.data() + 8, boot);
  write32(bytes.data() + 12, arg);
  write16(bytes.data() + 16, crc16(bytes.data(), 16));
  return bytes;
}

static bool issue(Recorder &recorder, uint8_t op, uint32_t session,
                  uint32_t boot, uint32_t arg, uint32_t now) {
  const auto bytes = command(op, session, boot, arg);
  return recorder.control(bytes.data(), bytes.size(), now);
}

static Sample reading(uint32_t now, float value = 1.0f) {
  Sample sample{};
  sample.timestamp = now;
  sample.ax = value;
  sample.ay = -value;
  sample.az = 9.8f;
  sample.gx = value + 2.0f;
  sample.gy = value + 3.0f;
  sample.gz = -value - 4.0f;
  sample.temperature = 25.5f + value;
  return sample;
}

template <size_t Capacity = 32> struct Fixture {
  Sample storage[Capacity]{};
  Recorder recorder;
  Fixture() {
    recorder.initialize(storage, Capacity, BOOT);
    recorder.setSensorHealthy(true);
  }
  void start(uint32_t now = 0, uint32_t session = SESSION) {
    assert(issue(recorder, START, session, 0, 0, now));
    assert(recorder.active());
  }
  void add(unsigned count, uint32_t first = 0) {
    for (unsigned i = 0; i < count; ++i) {
      assert(recorder.sample(reading(first + i * 40u, float(i + 1))));
    }
  }
};

struct Batch {
  uint8_t frame = 0;
  uint32_t lastAt = 0;
  std::vector<uint8_t> body;
  std::vector<std::vector<uint8_t>> fragments;
  uint16_t count() const { return read16(body.data() + 46); }
};

static Batch batch(Recorder &recorder, uint32_t now, uint16_t mtu) {
  Batch result;
  uint8_t out[600]{};
  uint8_t parts = 0;
  uint16_t total = 0;
  for (unsigned part = 0; !parts || part < parts; ++part, now += 5u) {
    const size_t length = recorder.nextFragment(now, mtu, out, sizeof(out));
    assert(length >= 8 && length <= size_t(mtu - 3));
    assert(out[0] == 0xD7 && out[1] == 0x52 && out[2] == 1);
    assert(out[4] == part);
    if (!part) {
      result.frame = out[3];
      parts = out[5];
      total = read16(out + 6);
      assert(parts != 0 && total >= 52);
      const size_t payload = mtu - 3u - 8u;
      assert(parts == (total + payload - 1u) / payload);
    }
    assert(out[3] == result.frame && out[5] == parts);
    assert(read16(out + 6) == total);
    result.fragments.emplace_back(out, out + length);
    result.body.insert(result.body.end(), out + 8, out + length);
    result.lastAt = now;
  }
  assert(result.body.size() == total);
  assert(result.count() <= 8);
  assert(total == 48u + result.count() * 32u + 4u);
  assert(read32(result.body.data() + total - 4u) ==
         crc32(result.body.data(), total - 4u));
  return result;
}

static void assertSample(const Batch &frame, unsigned index, const Sample &sample) {
  assert(index < frame.count());
  const uint8_t *bytes = frame.body.data() + 48u + index * 32u;
  assert(read32(bytes) == sample.timestamp);
  const float expected[] = {sample.ax, sample.ay, sample.az, sample.gx,
                            sample.gy, sample.gz, sample.temperature};
  for (unsigned i = 0; i < 7; ++i) {
    uint32_t bits = 0;
    std::memcpy(&bits, &expected[i], sizeof(bits));
    assert(read32(bytes + 4u + i * 4u) == bits);
  }
}

static void testControlIntegrity() {
  const uint8_t known[] = "123456789";
  assert(crc16(known, 9) == 0x29B1u);
  assert(crc32(known, 9) == 0xCBF43926u);
  const auto valid = command(START, SESSION, 0);
  for (size_t i = 0; i < valid.size(); ++i) {
    Fixture<> f;
    auto damaged = valid;
    damaged[i] ^= 1;
    assert(f.recorder.control(damaged.data(), damaged.size(), 100) == (i >= 2));
    assert(!f.recorder.active() && f.recorder.session() == 0);
  }
  Fixture<> f;
  assert(f.recorder.control(valid.data(), valid.size() - 1, 100));
  auto invalid = command(0xFF, SESSION, 0);
  assert(f.recorder.control(invalid.data(), invalid.size(), 100));
  invalid = valid;
  invalid[2] = 2;
  write16(invalid.data() + 16, crc16(invalid.data(), 16));
  assert(f.recorder.control(invalid.data(), invalid.size(), 100));
  assert(!f.recorder.active() && f.recorder.session() == 0);
  assert(f.recorder.nextSeq() == 0 && f.recorder.boot() == BOOT);
}

static void testFragmentation(uint16_t mtu) {
  Fixture<> f;
  f.start(100);
  f.add(10, 100);
  const Batch first = batch(f.recorder, 1000, mtu);
  assert(first.count() == 8);
  assert(read32(first.body.data()) == SESSION);
  assert(read32(first.body.data() + 4) == BOOT);
  assert(read32(first.body.data() + 8) == 0);
  assert(read32(first.body.data() + 12) == 10);
  assert(read32(first.body.data() + 16) == 0);
  assert(read32(first.body.data() + 36) == 100);
  assert((read16(first.body.data() + 44) & 1u) != 0);
  for (unsigned i = 0; i < first.count(); ++i) {
    assertSample(first, i, reading(100u + i * 40u, float(i + 1)));
  }
  auto damaged = first.body;
  damaged[48 + 7] ^= 0x40;
  assert(crc32(damaged.data(), damaged.size() - 4) !=
         read32(damaged.data() + damaged.size() - 4));
  assert(issue(f.recorder, ACK, SESSION, BOOT, 8, first.lastAt + 5));
  const Batch tail = batch(f.recorder, first.lastAt + 10, mtu);
  assert(tail.count() == 2 && read32(tail.body.data() + 8) == 8);
  assertSample(tail, 0, reading(420, 9));
  assertSample(tail, 1, reading(460, 10));
  if (mtu == 23) assert(first.fragments.size() == 26);
  if (mtu == 185) assert(first.fragments.size() == 2);
  if (mtu == 517) assert(first.fragments.size() == 1);
}

static void testFragmentPacingAndMtuChange() {
  Fixture<> f;
  f.start();
  f.add(8);
  uint8_t out[600]{};
  assert(f.recorder.nextFragment(1000, 23, out, sizeof(out)) == 20);
  const uint8_t firstFrame = out[3];
  assert(out[4] == 0);
  assert(f.recorder.nextFragment(1000, 23, out, sizeof(out)) == 0);
  assert(f.recorder.nextFragment(1004, 23, out, sizeof(out)) == 0);
  assert(f.recorder.nextFragment(1005, 23, out, sizeof(out)) == 20);
  assert(out[3] == firstFrame && out[4] == 1);
  const Batch reframed = batch(f.recorder, 1010, 185);
  assert(reframed.frame != firstFrame && reframed.count() == 8);
  assert(read32(reframed.body.data() + 8) == 0);
}

static void testAckBoundsAndIdentity() {
  Fixture<> f;
  f.start();
  f.add(12);
  assert(issue(f.recorder, ACK, SESSION, BOOT, 1, 500));
  assert(f.recorder.oldestSeq() == 0);
  const Batch first = batch(f.recorder, 1000, 517);
  assert(first.count() == 8);
  assert(issue(f.recorder, ACK, SESSION + 1, BOOT, 8, 1001));
  assert(issue(f.recorder, ACK, SESSION, BOOT + 1, 8, 1002));
  assert(issue(f.recorder, ACK, SESSION, 0, 8, 1003));
  assert(issue(f.recorder, ACK, SESSION, BOOT, 9, 1004));
  assert(f.recorder.oldestSeq() == 0);
  assert(issue(f.recorder, ACK, SESSION, BOOT, 4, 1005));
  assert(f.recorder.oldestSeq() == 4);
  assert(issue(f.recorder, ACK, SESSION, BOOT, 8, 1006));
  assert(f.recorder.oldestSeq() == 8 && f.recorder.nextSeq() == 12);
  const Batch second = batch(f.recorder, 1010, 517);
  assert(read32(second.body.data() + 8) == 8 && second.count() == 4);
}

static void testRetransmission() {
  Fixture<> f;
  f.start();
  f.add(8);
  const Batch first = batch(f.recorder, 1000, 517);
  uint8_t out[600]{};
  assert(f.recorder.nextFragment(2999, 517, out, sizeof(out)) == 0);
  const Batch retry = batch(f.recorder, 3000, 517);
  assert(retry.frame == first.frame && retry.body == first.body);
  assert(f.recorder.oldestSeq() == 0);
  assert(issue(f.recorder, ACK, SESSION, BOOT, 8, 3001));
  assert(f.recorder.oldestSeq() == 8);
}

static void testRingOverflowAndReadErrors() {
  Fixture<3> f;
  f.start();
  f.add(5);
  f.recorder.readError();
  f.recorder.readError();
  assert(f.recorder.nextSeq() == 5 && f.recorder.oldestSeq() == 2);
  assert(f.recorder.overflow() == 2 && f.recorder.readErrors() == 2);
  const Batch frame = batch(f.recorder, 1000, 517);
  assert(frame.count() == 3 && read32(frame.body.data() + 8) == 2);
  assert(read32(frame.body.data() + 20) == 2);
  assert(read32(frame.body.data() + 28) == 2);
  for (unsigned i = 0; i < 3; ++i) {
    assertSample(frame, i, reading((i + 2u) * 40u, float(i + 3)));
  }
  assert(issue(f.recorder, ACK, SESSION, BOOT, 5, 1001));
  assert(f.recorder.sample(reading(200, 6)));
  assert(f.recorder.oldestSeq() == 5 && f.recorder.overflow() == 2);
}

static void testSamplingCadenceAndWrap() {
  Fixture<> f;
  f.start(100);
  assert(!f.recorder.sample(reading(99)));
  assert(f.recorder.sample(reading(100)));
  assert(!f.recorder.sample(reading(100)));
  assert(!f.recorder.sample(reading(139)));
  assert(f.recorder.sample(reading(140)));
  assert(f.recorder.sample(reading(260)));
  assert(f.recorder.nextSeq() == 3 && f.recorder.missedSlots() == 2);

  Fixture<> wrap;
  wrap.start(0xFFFFFFF0u);
  assert(wrap.recorder.sample(reading(0xFFFFFFF0u)));
  assert(wrap.recorder.sample(reading(0x18u)));
  assert(wrap.recorder.missedSlots() == 0);
  assert(wrap.recorder.sample(reading(0x90u)));
  assert(wrap.recorder.missedSlots() == 2 && wrap.recorder.nextSeq() == 3);
  const Batch frame = batch(wrap.recorder, 0x100u, 517);
  assert(frame.count() == 3 && read32(frame.body.data() + 24) == 2);
  assertSample(frame, 0, reading(0xFFFFFFF0u));
  assertSample(frame, 1, reading(0x18u));
  assertSample(frame, 2, reading(0x90u));
}

static void testSessionLifecycle() {
  Fixture<3> f;
  f.start(100);
  f.add(5, 100);
  f.recorder.readError();
  assert(issue(f.recorder, START, SESSION, BOOT, 0, 400));
  assert(f.recorder.nextSeq() == 5 && f.recorder.oldestSeq() == 2);
  assert(f.recorder.overflow() == 2 && f.recorder.readErrors() == 1);
  assert(issue(f.recorder, STOP, SESSION + 1, BOOT, 0, 500));
  assert(issue(f.recorder, STOP, SESSION, BOOT + 1, 0, 500));
  assert(f.recorder.active());
  assert(issue(f.recorder, STOP, SESSION, 0, 0, 500));
  assert(f.recorder.stopped() && !f.recorder.active());
  assert(!f.recorder.sample(reading(540)));
  assert(issue(f.recorder, STOP, SESSION, BOOT, 0, 600));
  assert(issue(f.recorder, START, SESSION, BOOT, 0, 700));
  assert(f.recorder.stopped() && !f.recorder.active());
  const Batch stopped = batch(f.recorder, 1000, 517);
  assert(read32(stopped.body.data() + 36) == 100);
  assert(read32(stopped.body.data() + 40) == 500);
  assert((read16(stopped.body.data() + 44) & 3u) == 2u);
  assert(issue(f.recorder, START, SESSION + 1, 0, 0, 1100));
  assert(f.recorder.active() && !f.recorder.stopped());
  assert(f.recorder.session() == SESSION + 1 && f.recorder.boot() == BOOT);
  assert(f.recorder.nextSeq() == 0 && f.recorder.oldestSeq() == 0);
  assert(f.recorder.overflow() == 0 && f.recorder.readErrors() == 0);
  assert(f.recorder.missedSlots() == 0);
  assert(f.recorder.sample(reading(1100)));
}

static void testEmptyQueryAndHealth() {
  Fixture<> f;
  uint8_t out[600]{};
  assert(f.recorder.nextFragment(1000, 517, out, sizeof(out)) == 0);
  assert(issue(f.recorder, QUERY, 0, 0, 0, 1000));
  const Batch empty = batch(f.recorder, 1005, 517);
  assert(empty.count() == 0 && empty.body.size() == 52);
  assert(read32(empty.body.data()) == 0 && read32(empty.body.data() + 4) == BOOT);

  Sample storage[4]{};
  Recorder unhealthy;
  unhealthy.initialize(storage, 4, BOOT);
  assert(issue(unhealthy, START, SESSION, 0, 0, 100));
  assert(!unhealthy.sample(reading(100)) && unhealthy.nextSeq() == 0);
  const Batch status = batch(unhealthy, 1000, 517);
  assert(status.count() == 0 && (read16(status.body.data() + 44) & 4u));
  unhealthy.setSensorHealthy(true);
  assert(unhealthy.sample(reading(1040)));
  unhealthy.setSensorHealthy(false);
  assert(!unhealthy.sample(reading(1080)) && unhealthy.nextSeq() == 1);

  Recorder allocationFailed;
  allocationFailed.initialize(nullptr, 0, BOOT);
  allocationFailed.setSensorHealthy(true);
  assert(issue(allocationFailed, START, SESSION, 0, 0, 100));
  assert(!allocationFailed.sample(reading(100)));
  const Batch failed = batch(allocationFailed, 1000, 517);
  assert(failed.count() == 0 && (read16(failed.body.data() + 44) & 8u));
}

static void testDisconnectAndResume() {
  Fixture<> f;
  f.start();
  f.add(3);
  f.recorder.readError();
  const Batch delivered = batch(f.recorder, 1000, 517);
  assert(delivered.count() == 3);
  f.recorder.disconnected();
  assert(f.recorder.active() && !f.recorder.stopped());
  assert(f.recorder.nextSeq() == 3 && f.recorder.oldestSeq() == 0);
  assert(f.recorder.readErrors() == 1 && f.recorder.overflow() == 0);
  assert(f.recorder.sample(reading(120, 4)));
  issue(f.recorder, START, SESSION, BOOT + 1, 3, 1100);
  assert(f.recorder.oldestSeq() == 0); // Another boot cannot acknowledge retained data.
  assert(issue(f.recorder, START, SESSION, BOOT, 3, 1101));
  assert(f.recorder.oldestSeq() == 3 && f.recorder.nextSeq() == 4);
  assert(issue(f.recorder, ACK, SESSION, BOOT, 4, 1102));
  const Batch resumed = batch(f.recorder, 1110, 517);
  assert(resumed.count() == 1 && read32(resumed.body.data() + 8) == 3);
  assertSample(resumed, 0, reading(120, 4));

  Fixture<> partial;
  partial.start();
  partial.add(3);
  uint8_t out[600]{};
  assert(partial.recorder.nextFragment(1000, 23, out, sizeof(out)) == 20);
  partial.recorder.disconnected();
  assert(issue(partial.recorder, ACK, SESSION, BOOT, 1, 1001));
  assert(partial.recorder.oldestSeq() == 0 && partial.recorder.active());
}

static void testStopDrainAndPeriodicStatus() {
  Fixture<> f;
  f.start(100);
  f.add(10, 100);
  const Batch initial = batch(f.recorder, 1000, 517);
  issue(f.recorder, STOP, SESSION, BOOT, 0, 1040);
  assert(f.recorder.stopped());
  issue(f.recorder, ACK, SESSION, BOOT, 8, 1050);
  const Batch tail = batch(f.recorder, 1060, 517);
  assert(tail.count() == 2 && (read16(tail.body.data() + 44) & 3u) == 2u);
  assert(read32(tail.body.data() + 40) == 1040);
  issue(f.recorder, ACK, SESSION, BOOT, 10, 1070);
  // The stopped state must survive loss of either the tail batch or its ACK.
  uint8_t out[600]{};
  assert(f.recorder.nextFragment(2059, 517, out, sizeof(out)) == 0);
  const Batch empty = batch(f.recorder, 2060, 517);
  assert(empty.count() == 0 && read32(empty.body.data() + 8) == 10);
  assert((read16(empty.body.data() + 44) & 3u) == 2u);
  const Batch retry = batch(f.recorder, 4060, 517);
  assert(retry.frame == empty.frame && retry.body == empty.body);
  issue(f.recorder, ACK, SESSION, BOOT, 10, 4061);
  const Batch periodic = batch(f.recorder, 4070, 517);
  assert(periodic.count() == 0 && periodic.frame != retry.frame);
  assert(read32(periodic.body.data() + 32) == 4070);
  assert(initial.count() == 8);
}

static void testTransmitClockWrapAndOverwrite() {
  Fixture<> f;
  f.start();
  f.add(8);
  const Batch first = batch(f.recorder, 0xFFFFFFF0u, 517);
  uint8_t out[600]{};
  assert(f.recorder.nextFragment(0xFFFFFFF4u, 517, out, sizeof(out)) == 0);
  assert(f.recorder.nextFragment(0x7BFu, 517, out, sizeof(out)) == 0);
  const Batch retry = batch(f.recorder, 0x7C0u, 517);
  assert(retry.frame == first.frame && retry.body == first.body);

  Fixture<3> overflow;
  overflow.start();
  overflow.add(3);
  assert(overflow.recorder.nextFragment(1000, 23, out, sizeof(out)) == 20);
  const uint8_t oldFrame = out[3];
  assert(overflow.recorder.sample(reading(120, 4)));
  const Batch replacement = batch(overflow.recorder, 1005, 517);
  assert(replacement.frame != oldFrame);
  assert(read32(replacement.body.data() + 8) == 1);
  assert(read32(replacement.body.data() + 20) == 1);
  assertSample(replacement, 0, reading(40, 2));
}

static void testFiniteSamplesAndBadBuffers() {
  Fixture<> f;
  f.start();
  Sample invalid = reading(0);
  invalid.gz = INFINITY;
  assert(!f.recorder.sample(invalid));
  invalid.gz = 0;
  invalid.temperature = NAN;
  assert(!f.recorder.sample(invalid));
  assert(f.recorder.nextSeq() == 0);
  assert(f.recorder.sample(reading(0)));
  uint8_t out[600]{};
  assert(f.recorder.nextFragment(100, 22, out, sizeof(out)) == 0);
  assert(f.recorder.nextFragment(100, 23, nullptr, sizeof(out)) == 0);
  assert(f.recorder.nextFragment(100, 23, out, 8) == 0);
  assert(!f.recorder.control(nullptr, 18, 100));
}

static void testJitterKeepsSamplingPhase() {
  Fixture<> f;
  f.start(0);
  assert(f.recorder.sample(reading(0)));
  assert(f.recorder.sample(reading(60)));
  assert(f.recorder.sample(reading(120)));
  assert(f.recorder.sample(reading(180)));
  assert(f.recorder.nextSeq() == 4 && f.recorder.missedSlots() == 1);
  issue(f.recorder, STOP, SESSION, BOOT, 0, 240);
  assert(f.recorder.missedSlots() == 3);
  const Batch frame = batch(f.recorder, 1000, 517);
  assert(read32(frame.body.data() + 24) == 3);
  assertSample(frame, 1, reading(60));
  assertSample(frame, 2, reading(120));
}

static void testBatchWaitAndStopFlush() {
  Fixture<> f;
  f.start(0);
  const Batch initialStatus = batch(f.recorder, 0, 517);
  assert(initialStatus.count() == 0);
  issue(f.recorder, ACK, SESSION, BOOT, 0, 1);
  assert(f.recorder.sample(reading(0)));
  uint8_t out[600]{};
  assert(f.recorder.nextFragment(5, 517, out, sizeof(out)) == 0);
  for (unsigned i = 1; i <= 6; ++i)
    assert(f.recorder.sample(reading(i * 40u, float(i + 1))));
  assert(f.recorder.nextFragment(249, 517, out, sizeof(out)) == 0);
  const Batch collected = batch(f.recorder, 250, 517);
  assert(collected.count() == 7);
  issue(f.recorder, ACK, SESSION, BOOT, 7, 251);
  assert(f.recorder.sample(reading(280, 8)));
  assert(f.recorder.nextFragment(285, 517, out, sizeof(out)) == 0);
  issue(f.recorder, STOP, SESSION, BOOT, 0, 300);
  const Batch tail = batch(f.recorder, 300, 517);
  assert(tail.count() == 1 && (read16(tail.body.data() + 44) & 3u) == 2u);
  assertSample(tail, 0, reading(280, 8));
}

int main() {
  testControlIntegrity();
  testFragmentation(23);
  testFragmentation(185);
  testFragmentation(517);
  testFragmentPacingAndMtuChange();
  testAckBoundsAndIdentity();
  testRetransmission();
  testRingOverflowAndReadErrors();
  testSamplingCadenceAndWrap();
  testSessionLifecycle();
  testEmptyQueryAndHealth();
  testDisconnectAndResume();
  testStopDrainAndPeriodicStatus();
  testTransmitClockWrapAndOverwrite();
  testFiniteSamplesAndBadBuffers();
  testJitterKeepsSamplingPhase();
  testBatchWaitAndStopFlush();
  std::puts("PASS: continuous IMU CRC, MTUs, pacing, ACK bounds, retry, overflow, health, sessions and clock wrap");
}
