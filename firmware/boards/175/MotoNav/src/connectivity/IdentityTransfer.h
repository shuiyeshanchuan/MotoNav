#pragma once
#include <stdint.h>
#include <stddef.h>
#include <string.h>

// Optional complete INFO response, separate from legacy text and IMU frames.
// One bounded in-flight response, no heap allocation. notify has no delivery
// result in this BLE library; the phone requests again if completion is missing.
class IdentityTransfer {
 public:
  static constexpr size_t MAX_BODY = 160;
  bool begin(const char *text) {
    const size_t n = text ? strlen(text) : 0;
    reset();
    if (!n || n + 4 > MAX_BODY) return false;
    memcpy(body_, text, n);
    const uint32_t crc = checksum(body_, n);
    for (unsigned i = 0; i < 4; ++i) body_[n + i] = uint8_t(crc >> (8 * i));
    length_ = uint16_t(n + 4); ++frame_;
    return true;
  }
  void reset() { length_ = offset_ = prepared_ = 0; }
  size_t next(uint16_t mtu, uint8_t *out, size_t capacity) {
    prepared_ = 0;
    if (!length_ || offset_ >= length_ || mtu < 23 || capacity < 20) return 0;
    size_t n = length_ - offset_;
    if (n > size_t(mtu - 11)) n = mtu - 11;
    if (n > capacity - 8) n = capacity - 8;
    out[0] = 0xd7; out[1] = 0x49; out[2] = 1; out[3] = frame_;
    out[4] = uint8_t(offset_); out[5] = uint8_t(offset_ >> 8);
    out[6] = uint8_t(length_); out[7] = uint8_t(length_ >> 8);
    memcpy(out + 8, body_ + offset_, n); prepared_ = uint16_t(n);
    return n + 8;
  }
  void accepted() {
    offset_ += prepared_; prepared_ = 0;
    if (offset_ == length_) reset();
  }
  static uint32_t checksum(const uint8_t *bytes, size_t n) {
    uint32_t crc = 0xffffffff;
    for (size_t i = 0; i < n; ++i) {
      crc ^= bytes[i];
      for (unsigned b = 0; b < 8; ++b) crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1)));
    }
    return ~crc;
  }
 private:
  uint8_t body_[MAX_BODY] = {};
  uint16_t length_ = 0, offset_ = 0, prepared_ = 0;
  uint8_t frame_ = 0;
};
