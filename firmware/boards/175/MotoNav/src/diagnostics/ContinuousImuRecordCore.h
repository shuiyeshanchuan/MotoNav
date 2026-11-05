#pragma once

#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <math.h>

// Record-only telemetry. No thresholds, calibration or accident decisions live
// here. The main-loop reader supplies real samples; missed slots are not filled.
namespace ContinuousImu {

static constexpr uint32_t SAMPLE_CAPACITY = 4096;
static constexpr uint32_t SAMPLE_INTERVAL_MS = 40;
static constexpr size_t CONTROL_SIZE = 18;
static constexpr size_t FRAGMENT_HEADER_SIZE = 8;
static constexpr size_t BODY_HEADER_SIZE = 48;
static constexpr uint16_t MAX_BATCH_SAMPLES = 8;
static constexpr size_t MAX_BODY_SIZE = BODY_HEADER_SIZE + MAX_BATCH_SAMPLES * 32 + 4;
static constexpr size_t MAX_FRAGMENT_SIZE = FRAGMENT_HEADER_SIZE + MAX_BODY_SIZE;

inline uint16_t read16(const uint8_t *p) {
  return uint16_t(p[0]) | (uint16_t(p[1]) << 8);
}
inline uint32_t read32(const uint8_t *p) {
  return uint32_t(p[0]) | (uint32_t(p[1]) << 8) |
      (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
inline void write16(uint8_t *p, uint16_t v) {
  p[0] = uint8_t(v); p[1] = uint8_t(v >> 8);
}
inline void write32(uint8_t *p, uint32_t v) {
  p[0] = uint8_t(v); p[1] = uint8_t(v >> 8);
  p[2] = uint8_t(v >> 16); p[3] = uint8_t(v >> 24);
}
inline void writeFloat(uint8_t *p, float value) {
  uint32_t bits;
  memcpy(&bits, &value, sizeof(bits));
  write32(p, bits);
}
inline uint16_t crc16(const uint8_t *p, size_t n) {
  uint16_t crc = 0xFFFF;
  for (size_t i = 0; i < n; ++i) {
    crc ^= uint16_t(p[i]) << 8;
    for (uint8_t bit = 0; bit < 8; ++bit)
      crc = (crc & 0x8000) ? uint16_t((crc << 1) ^ 0x1021) : uint16_t(crc << 1);
  }
  return crc;
}
inline uint32_t crc32(const uint8_t *p, size_t n) {
  uint32_t crc = 0xFFFFFFFF;
  for (size_t i = 0; i < n; ++i) {
    crc ^= p[i];
    for (uint8_t bit = 0; bit < 8; ++bit)
      crc = (crc >> 1) ^ ((crc & 1) ? 0xEDB88320U : 0U);
  }
  return crc ^ 0xFFFFFFFF;
}

struct Sample {
  uint32_t timestamp;
  float ax, ay, az, gx, gy, gz, temperature;
};
static_assert(sizeof(float) == 4 && sizeof(Sample) == 32, "IMU recording sample layout");

class Recorder {
public:
  void initialize(Sample *storage, uint32_t capacity, uint32_t boot) {
    storage_ = storage;
    capacity_ = storage ? capacity : 0;
    boot_ = boot ? boot : 1;
  }

  // A matching prefix is always consumed, including invalid controls, so a bad
  // binary frame can never reach the navigation parser or refresh its lease.
  bool control(const uint8_t *data, size_t n, uint32_t now) {
    if (!data || n < 2 || data[0] != 0xD7 || data[1] != 0x43) return false;
    if (n != CONTROL_SIZE || data[2] != 1 || crc16(data, 16) != read16(data + 16)) return true;
    const uint8_t opcode = data[3];
    const uint32_t session = read32(data + 4);
    const uint32_t boot = read32(data + 8);
    const uint32_t arg = read32(data + 12);
    if (opcode == 1 && session) {
      if (session != session_) start(session, now);
      else if (boot == boot_) acknowledge(arg);
      // A late duplicate START must never re-arm a stopped session.
      statusPending_ = true;
    } else if (opcode == 2 && session && session == session_ && (!boot || boot == boot_)) {
      if (active_) {
        accountStopGap(now);
        active_ = false; stopped_ = true; stopMillis_ = now;
      }
      statusPending_ = true;
    } else if (opcode == 3 && session && session == session_ && boot == boot_) {
      acknowledge(arg);
    } else if (opcode == 4 && (!session || session == session_)) {
      statusPending_ = true;
    }
    return true;
  }

  bool sample(const Sample &entry) {
    if (!active_ || !storage_ || !capacity_ || !sensorHealthy_) return false;
    if (!isfinite(entry.ax) || !isfinite(entry.ay) || !isfinite(entry.az) ||
        !isfinite(entry.gx) || !isfinite(entry.gy) || !isfinite(entry.gz) ||
        !isfinite(entry.temperature)) return false;
    if (haveSample_ && int32_t(entry.timestamp - lastSampleAt_) < 0) return false;
    const uint32_t elapsed = entry.timestamp - nextSampleAt_;
    if (int32_t(elapsed) < 0) return false;
    // Keep the original 40 ms phase. A 60 ms main loop must expose the missed
    // slots instead of silently drifting to 16.7 Hz with a zero gap counter.
    const uint32_t slots = elapsed / SAMPLE_INTERVAL_MS;
    missedSlots_ += slots;
    nextSampleAt_ += (slots + 1) * SAMPLE_INTERVAL_MS;
    haveSample_ = true;
    lastSampleAt_ = entry.timestamp;
    if (nextSeq_ - oldestSeq_ == capacity_) {
      ++oldestSeq_; ++overflow_;
      // An overwritten batch must not prevent publishing the new gap boundary.
      if (inFlight_ && int32_t(oldestSeq_ - flightFirst_) > 0) inFlight_ = false;
    }
    storage_[nextSeq_ % capacity_] = entry;
    ++nextSeq_;
    return true;
  }

  void readError() { if (active_) ++readErrors_; }
  void setSensorHealthy(bool healthy) {
    if (healthy != sensorHealthy_ && session_) statusPending_ = true;
    sensorHealthy_ = healthy;
  }
  void disconnected() {
    inFlight_ = false;
    statusPending_ = session_ != 0;
    // Ring, session and sampling survive a temporary BLE outage.
  }

  // At most one fragment per call. The caller shares the existing main-loop
  // notify channel and must skip calls while disconnected, unauthenticated or OTA.
  size_t nextFragment(uint32_t now, uint16_t mtu, uint8_t *out, size_t capacity) {
    if (!out || mtu < 23 || capacity <= FRAGMENT_HEADER_SIZE) return 0;
    if (haveSent_ && uint32_t(now - lastFragmentAt_) < 5) return 0;
    size_t payload = size_t(mtu - 3) - FRAGMENT_HEADER_SIZE;
    if (payload > capacity - FRAGMENT_HEADER_SIZE) payload = capacity - FRAGMENT_HEADER_SIZE;
    if (payload > MAX_BODY_SIZE) payload = MAX_BODY_SIZE;
    if (!payload) return 0;

    // A zero-sample status is superseded by real data, rather than holding the
    // entire recording behind an ACK for an empty frame.
    if (inFlight_ && flightCount_ == 0 && nextSeq_ != oldestSeq_) inFlight_ = false;
    if (!inFlight_) {
      const uint32_t available = nextSeq_ - oldestSeq_;
      if (active_ && available && available < MAX_BATCH_SAMPLES && !statusPending_ &&
          uint32_t(now - storage_[oldestSeq_ % capacity_].timestamp) < 250) return 0;
      if (nextSeq_ == oldestSeq_ && !statusPending_ &&
          (!session_ || uint32_t(now - lastStatusAt_) < 1000)) return 0;
      prepareBody(now);
      fragmentPayload_ = payload;
      partIndex_ = 0;
      partCount_ = uint8_t((bodyLength_ + payload - 1) / payload);
      ++frameId_;
      inFlight_ = true;
    } else if (payload != fragmentPayload_) {
      // MTU can change on reconnect. A new id prevents mixed-size reassembly.
      fragmentPayload_ = payload;
      partIndex_ = 0;
      partCount_ = uint8_t((bodyLength_ + payload - 1) / payload);
      ++frameId_;
    } else if (partIndex_ == partCount_) {
      if (uint32_t(now - completedAt_) < 2000) return 0;
      partIndex_ = 0;  // Retransmit the immutable body, with the same frame id.
    }

    const size_t offset = size_t(partIndex_) * fragmentPayload_;
    const size_t remaining = bodyLength_ - offset;
    const size_t take = remaining < fragmentPayload_ ? remaining : fragmentPayload_;
    out[0] = 0xD7; out[1] = 0x52; out[2] = 1; out[3] = frameId_;
    out[4] = partIndex_; out[5] = partCount_;
    write16(out + 6, uint16_t(bodyLength_));
    memcpy(out + FRAGMENT_HEADER_SIZE, body_ + offset, take);
    ++partIndex_;
    if (partIndex_ == partCount_) {
      completedAt_ = now;
      const uint32_t end = flightFirst_ + flightCount_;
      if (int32_t(end - highestOfferedNext_) > 0) highestOfferedNext_ = end;
    }
    haveSent_ = true;
    lastFragmentAt_ = now;
    return take + FRAGMENT_HEADER_SIZE;
  }

  uint32_t session() const { return session_; }
  uint32_t boot() const { return boot_; }
  uint32_t nextSeq() const { return nextSeq_; }
  uint32_t oldestSeq() const { return oldestSeq_; }
  uint32_t overflow() const { return overflow_; }
  uint32_t missedSlots() const { return missedSlots_; }
  uint32_t readErrors() const { return readErrors_; }
  bool active() const { return active_; }
  bool stopped() const { return stopped_; }

private:
  void start(uint32_t session, uint32_t now) {
    session_ = session;
    nextSeq_ = oldestSeq_ = highestOfferedNext_ = 0;
    overflow_ = missedSlots_ = readErrors_ = 0;
    startMillis_ = now; stopMillis_ = 0;
    nextSampleAt_ = now;
    haveSample_ = false; active_ = true; stopped_ = false;
    inFlight_ = false; statusPending_ = true;
  }

  void accountStopGap(uint32_t now) {
    if (int32_t(now - nextSampleAt_) >= 0)
      missedSlots_ += uint32_t(now - nextSampleAt_) / SAMPLE_INTERVAL_MS + 1;
  }

  void acknowledge(uint32_t durableNext) {
    // Never free unsent/future samples. Reconnect retains the high-water mark
    // of complete batches, so a durable ACK lost during disconnect still works.
    if (int32_t(durableNext - highestOfferedNext_) > 0 ||
        durableNext - oldestSeq_ > nextSeq_ - oldestSeq_) return;
    oldestSeq_ = durableNext;
    if (inFlight_ && int32_t(durableNext - (flightFirst_ + flightCount_)) >= 0)
      inFlight_ = false;
  }

  void prepareBody(uint32_t now) {
    flightFirst_ = oldestSeq_;
    const uint32_t available = nextSeq_ - oldestSeq_;
    flightCount_ = uint16_t(available < MAX_BATCH_SAMPLES ? available : MAX_BATCH_SAMPLES);
    write32(body_, session_); write32(body_ + 4, boot_);
    write32(body_ + 8, flightFirst_); write32(body_ + 12, nextSeq_);
    write32(body_ + 16, oldestSeq_); write32(body_ + 20, overflow_);
    write32(body_ + 24, missedSlots_); write32(body_ + 28, readErrors_);
    write32(body_ + 32, now); write32(body_ + 36, startMillis_);
    write32(body_ + 40, stopMillis_);
    const uint16_t flags = (active_ ? 1 : 0) | (stopped_ ? 2 : 0) |
        (sensorHealthy_ ? 0 : 4) | (storage_ && capacity_ ? 0 : 8);
    write16(body_ + 44, flags); write16(body_ + 46, flightCount_);
    for (uint16_t i = 0; i < flightCount_; ++i) {
      const Sample &s = storage_[(flightFirst_ + i) % capacity_];
      uint8_t *p = body_ + BODY_HEADER_SIZE + size_t(i) * 32;
      write32(p, s.timestamp);
      writeFloat(p + 4, s.ax); writeFloat(p + 8, s.ay); writeFloat(p + 12, s.az);
      writeFloat(p + 16, s.gx); writeFloat(p + 20, s.gy); writeFloat(p + 24, s.gz);
      writeFloat(p + 28, s.temperature);
    }
    bodyLength_ = BODY_HEADER_SIZE + size_t(flightCount_) * 32 + 4;
    write32(body_ + bodyLength_ - 4, crc32(body_, bodyLength_ - 4));
    statusPending_ = false;
    lastStatusAt_ = now;
  }

  Sample *storage_ = nullptr;
  uint32_t capacity_ = 0, boot_ = 1, session_ = 0;
  uint32_t nextSeq_ = 0, oldestSeq_ = 0, highestOfferedNext_ = 0;
  uint32_t overflow_ = 0, missedSlots_ = 0, readErrors_ = 0;
  uint32_t startMillis_ = 0, stopMillis_ = 0, lastSampleAt_ = 0, nextSampleAt_ = 0;
  bool active_ = false, stopped_ = false, sensorHealthy_ = false, haveSample_ = false;
  bool statusPending_ = false, inFlight_ = false, haveSent_ = false;
  uint32_t lastStatusAt_ = 0, lastFragmentAt_ = 0, completedAt_ = 0;
  uint32_t flightFirst_ = 0;
  uint16_t flightCount_ = 0;
  uint8_t frameId_ = 0, partIndex_ = 0, partCount_ = 0;
  size_t fragmentPayload_ = 0, bodyLength_ = 0;
  uint8_t body_[MAX_BODY_SIZE] = {};
};

}  // namespace ContinuousImu
