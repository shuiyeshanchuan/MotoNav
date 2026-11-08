#pragma once

#include <stddef.h>
#include <stdint.h>

namespace ImuAcquisition {

struct Event {
  uint32_t timestamp = 0, readErrors = 0;
  float ax = 0, ay = 0, az = 0, gx = 0, gy = 0, gz = 0, temperature = 0;
  int16_t rawAccel[3] = {}, rawGyro[3] = {};
  bool valid = false;
};
static_assert(sizeof(Event) == 52, "IMU acquisition event memory budget");

struct Snapshot {
  size_t count;
  uint32_t dropped, readErrors;
};

// Caller serializes every operation. On ESP32 this is a short portMUX region;
// no I2C, formatting, NVS, BLE or consumer callbacks may run inside that region.
// Drop newest when full: a consumer snapshot remains a stable FIFO prefix.
template <size_t Capacity> class Queue {
public:
  bool push(Event event) {
    if (!event.valid) ++readErrors_;
    event.readErrors = readErrors_;
    if (count_ == Capacity) { ++dropped_; return false; }
    entries_[(head_ + count_) % Capacity] = event;
    ++count_;
    return true;
  }
  bool pop(Event &event) {
    if (!count_) return false;
    event = entries_[head_];
    head_ = (head_ + 1) % Capacity;
    --count_;
    return true;
  }
  Snapshot snapshot() const { return {count_, dropped_, readErrors_}; }

private:
  static_assert(Capacity > 0, "IMU acquisition queue capacity");
  Event entries_[Capacity] = {};
  size_t head_ = 0, count_ = 0;
  uint32_t dropped_ = 0, readErrors_ = 0;
};

}  // namespace ImuAcquisition
