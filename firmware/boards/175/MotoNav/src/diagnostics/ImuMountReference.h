#pragma once

// Installation reference only: no sensor bias or accident thresholds are changed.
// Kept free of Arduino APIs so mathematics, persistence and collection can be tested natively.
#include <math.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>

namespace ImuMount {

static constexpr uint32_t SCHEMA = 1;
static constexpr uint32_t MAGIC = 0x4D4E4D52UL;
static constexpr uint32_t CAPTURE_MS = 5000;
static constexpr uint32_t TIMEOUT_MS = 8000;
static constexpr uint32_t MAX_GAP_MS = 75;
static constexpr uint32_t MIN_SAMPLES = 200;

enum State : uint8_t { UNSUPPORTED = 0, EMPTY = 1, COLLECTING = 2, READY = 3, FAILED = 4 };
enum Reason : uint8_t { NONE = 0, MOVED = 1, SENSOR = 2, GAP = 3, SAVE = 4, TIMEOUT = 5, SPEED = 6 };

inline bool gravityValid(float x, float y, float z) {
  if (!isfinite(x) || !isfinite(y) || !isfinite(z)) return false;
  const float norm = sqrtf(x * x + y * y + z * z);
  return isfinite(norm) && norm >= 7.5f && norm <= 12.5f;
}

// acos(dot(normalize(reference), normalize(sample))), with direction retained.
// Static relative total tilt, not signed motorcycle lean. UI rotation never enters this formula.
inline float relativeTiltDeg(float rx, float ry, float rz, float ax, float ay, float az) {
  if (!gravityValid(rx, ry, rz) || !isfinite(ax) || !isfinite(ay) || !isfinite(az)) return NAN;
  const float referenceNorm = sqrtf(rx * rx + ry * ry + rz * rz);
  const float sampleNorm = sqrtf(ax * ax + ay * ay + az * az);
  if (!isfinite(sampleNorm) || sampleNorm <= 0.000001f || sampleNorm > 200.0f) return NAN;
  float dot = (rx * ax + ry * ay + rz * az) / (referenceNorm * sampleNorm);
  if (!isfinite(dot)) return NAN;
  if (dot > 1.0f) dot = 1.0f;
  if (dot < -1.0f) dot = -1.0f;
  return acosf(dot) * 57.2957795131f;
}

// All fields are four bytes, with a checksum over the complete preceding byte sequence.
// Preferences stores this as one NVS blob; no partially updated group of scalar keys exists.
struct Reference {
  uint32_t magic;
  uint32_t schema;
  uint32_t revision;
  float ax, ay, az;
  uint32_t checksum;
};
static_assert(sizeof(float) == 4 && sizeof(Reference) == 28, "mount reference schema size");

inline uint32_t checksum(const Reference &reference) {
  uint32_t hash = 2166136261UL;
  const uint8_t *bytes = reinterpret_cast<const uint8_t *>(&reference);
  for (size_t i = 0; i < offsetof(Reference, checksum); ++i) hash = (hash ^ bytes[i]) * 16777619UL;
  return hash;
}
inline bool referenceValid(const Reference &reference) {
  return reference.magic == MAGIC && reference.schema == SCHEMA && reference.revision > 0 &&
      gravityValid(reference.ax, reference.ay, reference.az) && reference.checksum == checksum(reference);
}
inline Reference makeReference(uint32_t revision, float ax, float ay, float az) {
  Reference reference = {MAGIC, SCHEMA, revision, ax, ay, az, 0};
  reference.checksum = checksum(reference);
  return reference;
}

struct AxisStats {
  float mean = 0.0f, m2 = 0.0f, minimum = 0.0f, maximum = 0.0f;
  void add(float value, uint32_t count) {
    if (count == 1) { mean = minimum = maximum = value; m2 = 0.0f; return; }
    if (value < minimum) minimum = value;
    if (value > maximum) maximum = value;
    const float delta = value - mean;
    mean += delta / static_cast<float>(count);
    m2 += delta * (value - mean);
  }
  bool moving(uint32_t count, float maxStdDev, float maxRange) const {
    return maximum - minimum > maxRange ||
        (count > 1 && m2 / static_cast<float>(count - 1) > maxStdDev * maxStdDev);
  }
};

struct Collector {
  State state = EMPTY;
  Reason reason = NONE;
  uint32_t count = 0, startedAt = 0, firstAt = 0, lastAt = 0;
  AxisStats accel[3], gyro[3];

  void start(uint32_t now, bool healthy) {
    *this = Collector();
    startedAt = now;
    state = healthy ? COLLECTING : FAILED;
    reason = healthy ? NONE : SENSOR;
  }
  void fail(Reason why) { if (state == COLLECTING || state == READY) { state = FAILED; reason = why; } }
  void tick(uint32_t now, bool healthy) {
    if (state != COLLECTING) return;
    if (!healthy) { fail(SENSOR); return; }
    if (now - startedAt >= TIMEOUT_MS) { fail(TIMEOUT); return; }
    if (count > 0 && now - lastAt > MAX_GAP_MS) fail(GAP);
    else if (count == 0 && now - startedAt > MAX_GAP_MS) fail(GAP);
  }
  void add(uint32_t now, float ax, float ay, float az, float gx, float gy, float gz,
           bool healthy = true, bool saturated = false) {
    if (state != COLLECTING) return;
    tick(now, healthy);
    if (state != COLLECTING) return;
    if (saturated || !isfinite(gx) || !isfinite(gy) || !isfinite(gz) ||
        !isfinite(ax) || !isfinite(ay) || !isfinite(az)) { fail(SENSOR); return; }
    if (!gravityValid(ax, ay, az)) { fail(MOVED); return; }
    if (count > 0 && now == lastAt) return; // One native sample cannot count twice.
    if (count == 0) firstAt = now;
    lastAt = now;
    ++count;
    const float a[] = {ax, ay, az}, g[] = {gx, gy, gz};
    for (int i = 0; i < 3; ++i) {
      accel[i].add(a[i], count);
      gyro[i].add(g[i], count);
      // Gyro is checked for changes only: stable -7 dps board bias is retained as-is.
      if (accel[i].moving(count, 0.08f, 0.50f) || gyro[i].moving(count, 0.30f, 2.0f)) {
        fail(MOVED);
        return;
      }
    }
    if (count >= MIN_SAMPLES && now - firstAt >= CAPTURE_MS) {
      if (!gravityValid(accel[0].mean, accel[1].mean, accel[2].mean)) { fail(MOVED); return; }
      state = READY; // Adapter must persist successfully before publishing READY.
    }
  }
};

}  // namespace ImuMount
