#pragma once

// QMI8658 diagnostics are deliberately opt-in.  The normal navigation image
// must not touch the IMU until the board wiring has been verified on a real
// 1.75C.  Build a diagnostic image with -DMOTONAV_IMU_DIAGNOSTICS=1.
#ifndef MOTONAV_IMU_DIAGNOSTICS
#define MOTONAV_IMU_DIAGNOSTICS 0
#endif

#if MOTONAV_IMU_DIAGNOSTICS

#include <ImuDrv.hpp>
#include <math.h>

// The diagnostic image keeps a small, fixed-size summary ring.  It is not an
// accident detector and it is never used to change navigation state.  Keeping
// only quantised magnitudes makes the memory cost bounded and avoids retaining
// a raw motion recording in the firmware process.
static constexpr uint8_t IMU_DIAG_RING_SIZE = 64;
static constexpr uint32_t IMU_DIAG_GAP_MS = 30;
static constexpr float IMU_DIAG_GRAVITY_MIN = 7.5f;
static constexpr float IMU_DIAG_GRAVITY_MAX = 12.5f;
static constexpr float IMU_DIAG_STILL_GRAVITY_MIN = 8.5f;
static constexpr float IMU_DIAG_STILL_GRAVITY_MAX = 10.8f;
static constexpr float IMU_DIAG_STILL_GYRO_MAX = 8.0f;

enum ImuDiagnosticSummaryFlags : uint8_t {
  IMU_DIAG_GRAVITY_OUT = 1 << 0,
  IMU_DIAG_ACCEL_SATURATED = 1 << 1,
  IMU_DIAG_GYRO_SATURATED = 1 << 2,
  IMU_DIAG_SAMPLE_GAP = 1 << 3,
  IMU_DIAG_STILL_LIKE = 1 << 4,
};

struct ImuDiagnosticSummary {
  uint32_t boardMillis;
  uint16_t accelMagnitudeCenti;  // m/s2 * 100, clamped to uint16_t
  uint16_t gyroMagnitudeDeci;    // dps * 10, clamped to uint16_t
  uint16_t jerkCenti;             // m/s3 * 100, clamped to uint16_t
  uint8_t flags;
};

inline uint16_t imuDiagnosticQuantize(float value, float scale) {
  if (!isfinite(value) || value <= 0.0f) return 0;
  const float scaled = value * scale;
  if (scaled >= 65535.0f) return 65535;
  return static_cast<uint16_t>(scaled + 0.5f);
}

inline bool imuDiagnosticRawSaturated(int16_t value) {
  return value >= 32700 || value <= -32700;
}

// This module only probes/configures the sensor and prints samples to Serial.
// It never scores an event, changes the navigation state, or sends BLE data.
struct ImuDiagnosticsState {
  SensorQMI8658 sensor;
  bool beginAttempted = false;
  bool beginOk = false;
  bool present = false;
  bool configured = false;
  bool selfTestAttempted = false;
  bool selfTestOk = false;
  uint8_t address = 0;
  uint8_t whoAmI = 0xFF;
  uint32_t firmwareVersion = 0;
  uint32_t samples = 0;
  uint32_t readErrors = 0;
  uint32_t lastSampleAt = 0;
  uint32_t lastSummaryAt = 0;
  uint32_t lastNotifyAt = 0;
  uint32_t lastHealthNotifyAt = 0;
  bool streamEnabled = false;
  bool statusPending = false;
  float ax = NAN, ay = NAN, az = NAN;
  float gx = NAN, gy = NAN, gz = NAN;
  float temperature = NAN;

  // Health counters are diagnostic evidence only.  None of these counters
  // arms, scores, or escalates an accident event.
  uint32_t sampleGaps = 0;
  uint32_t gravityInRange = 0;
  uint32_t gravityOutOfRange = 0;
  uint32_t stillLike = 0;
  uint32_t accelSaturated = 0;
  uint32_t gyroSaturated = 0;
  uint32_t intervalMinMs = 0;
  uint32_t intervalMaxMs = 0;
  uint32_t lastGoodSampleAt = 0;
  bool havePreviousSample = false;
  float lastAccelMagnitude = NAN;
  bool havePreviousMagnitude = false;
  uint16_t ringHead = 0;
  uint16_t ringCount = 0;
  ImuDiagnosticSummary ring[IMU_DIAG_RING_SIZE] = {};
};

ImuDiagnosticsState imuDiagnostics;

inline void recordImuDiagnosticSample(uint32_t now,
                                      const AccelerometerData &accel,
                                      const GyroscopeData &gyro) {
  const float accelMagnitude = sqrtf(
      accel.mps2.x * accel.mps2.x + accel.mps2.y * accel.mps2.y +
      accel.mps2.z * accel.mps2.z);
  const float gyroMagnitude = sqrtf(
      gyro.dps.x * gyro.dps.x + gyro.dps.y * gyro.dps.y +
      gyro.dps.z * gyro.dps.z);
  const bool gravityOk = isfinite(accelMagnitude) &&
                         accelMagnitude >= IMU_DIAG_GRAVITY_MIN &&
                         accelMagnitude <= IMU_DIAG_GRAVITY_MAX;
  const bool stillLike = isfinite(accelMagnitude) && isfinite(gyroMagnitude) &&
                         accelMagnitude >= IMU_DIAG_STILL_GRAVITY_MIN &&
                         accelMagnitude <= IMU_DIAG_STILL_GRAVITY_MAX &&
                         gyroMagnitude <= IMU_DIAG_STILL_GYRO_MAX;
  const bool accelSaturated = imuDiagnosticRawSaturated(accel.raw.x) ||
                              imuDiagnosticRawSaturated(accel.raw.y) ||
                              imuDiagnosticRawSaturated(accel.raw.z);
  const bool gyroSaturated = imuDiagnosticRawSaturated(gyro.raw.x) ||
                             imuDiagnosticRawSaturated(gyro.raw.y) ||
                             imuDiagnosticRawSaturated(gyro.raw.z);

  uint32_t intervalMs = 0;
  bool sampleGap = false;
  if (imuDiagnostics.havePreviousSample) {
    intervalMs = now - imuDiagnostics.lastGoodSampleAt;
    sampleGap = intervalMs > IMU_DIAG_GAP_MS;
    if (imuDiagnostics.intervalMinMs == 0 ||
        intervalMs < imuDiagnostics.intervalMinMs) {
      imuDiagnostics.intervalMinMs = intervalMs;
    }
    if (intervalMs > imuDiagnostics.intervalMaxMs) {
      imuDiagnostics.intervalMaxMs = intervalMs;
    }
    if (sampleGap) ++imuDiagnostics.sampleGaps;
  }
  imuDiagnostics.havePreviousSample = true;
  imuDiagnostics.lastGoodSampleAt = now;

  float jerk = 0.0f;
  if (imuDiagnostics.havePreviousMagnitude && intervalMs > 0 &&
      isfinite(imuDiagnostics.lastAccelMagnitude) &&
      isfinite(accelMagnitude)) {
    jerk = fabsf(accelMagnitude - imuDiagnostics.lastAccelMagnitude) /
           (intervalMs / 1000.0f);
  }
  imuDiagnostics.lastAccelMagnitude = accelMagnitude;
  imuDiagnostics.havePreviousMagnitude = true;

  if (gravityOk) ++imuDiagnostics.gravityInRange;
  else ++imuDiagnostics.gravityOutOfRange;
  if (stillLike) ++imuDiagnostics.stillLike;
  if (accelSaturated) ++imuDiagnostics.accelSaturated;
  if (gyroSaturated) ++imuDiagnostics.gyroSaturated;

  ImuDiagnosticSummary &entry = imuDiagnostics.ring[imuDiagnostics.ringHead];
  entry.boardMillis = now;
  entry.accelMagnitudeCenti = imuDiagnosticQuantize(accelMagnitude, 100.0f);
  entry.gyroMagnitudeDeci = imuDiagnosticQuantize(gyroMagnitude, 10.0f);
  entry.jerkCenti = imuDiagnosticQuantize(jerk, 100.0f);
  entry.flags = 0;
  if (!gravityOk) entry.flags |= IMU_DIAG_GRAVITY_OUT;
  if (accelSaturated) entry.flags |= IMU_DIAG_ACCEL_SATURATED;
  if (gyroSaturated) entry.flags |= IMU_DIAG_GYRO_SATURATED;
  if (sampleGap) entry.flags |= IMU_DIAG_SAMPLE_GAP;
  if (stillLike) entry.flags |= IMU_DIAG_STILL_LIKE;
  imuDiagnostics.ringHead = (imuDiagnostics.ringHead + 1) % IMU_DIAG_RING_SIZE;
  if (imuDiagnostics.ringCount < IMU_DIAG_RING_SIZE) ++imuDiagnostics.ringCount;
}

inline int formatImuDiagnosticHealth(char *out, size_t capacity) {
  if (!out || capacity == 0) return -1;
  const float accelMagnitude = imuDiagnostics.havePreviousMagnitude &&
                                       isfinite(imuDiagnostics.lastAccelMagnitude)
                                   ? imuDiagnostics.lastAccelMagnitude
                                   : 0.0f;
  const float gyroMagnitude =
      sqrtf(imuDiagnostics.gx * imuDiagnostics.gx +
            imuDiagnostics.gy * imuDiagnostics.gy +
            imuDiagnostics.gz * imuDiagnostics.gz);
  return snprintf(
      out, capacity,
      "IMU,HEALTH,1,%lu,%lu,%lu,%lu,%lu,%lu,%lu,%lu,%lu,%lu,%u,%u,%u",
      static_cast<unsigned long>(imuDiagnostics.samples),
      static_cast<unsigned long>(imuDiagnostics.readErrors),
      static_cast<unsigned long>(imuDiagnostics.sampleGaps),
      static_cast<unsigned long>(imuDiagnostics.gravityInRange),
      static_cast<unsigned long>(imuDiagnostics.gravityOutOfRange),
      static_cast<unsigned long>(imuDiagnostics.stillLike),
      static_cast<unsigned long>(imuDiagnostics.accelSaturated),
      static_cast<unsigned long>(imuDiagnostics.gyroSaturated),
      static_cast<unsigned long>(imuDiagnostics.intervalMinMs),
      static_cast<unsigned long>(imuDiagnostics.intervalMaxMs),
      static_cast<unsigned int>(imuDiagnostics.ringCount),
      static_cast<unsigned int>(imuDiagnosticQuantize(accelMagnitude, 100.0f)),
      static_cast<unsigned int>(imuDiagnosticQuantize(gyroMagnitude, 10.0f)));
}

inline void initImuDiagnostics() {
  if (imuDiagnostics.beginAttempted) return;
  imuDiagnostics.beginAttempted = true;

  Serial.printf("IMU diag: begin SDA=%d SCL=%d addr candidates=0x%02X/0x%02X\n",
                IIC_SDA, IIC_SCL, QMI8658_L_SLAVE_ADDRESS,
                QMI8658_H_SLAVE_ADDRESS);

  // SensorLib begin performs the device reset and WHO_AM_I probe.  Try both
  // legal QMI8658 addresses because the SA0 strap is board dependent.  A
  // failed begin clears the driver's I2C state, so the same object can safely
  // retry the second address.
  const uint8_t addresses[] = {QMI8658_L_SLAVE_ADDRESS,
                               QMI8658_H_SLAVE_ADDRESS};
  for (uint8_t candidate : addresses) {
    Serial.printf("IMU diag: probing addr=0x%02X\n", candidate);
    if (!imuDiagnostics.sensor.begin(Wire, candidate, IIC_SDA, IIC_SCL)) {
      continue;
    }
    imuDiagnostics.address = candidate;
    imuDiagnostics.beginOk = true;
    break;
  }
  if (!imuDiagnostics.beginOk) {
    Serial.println("IMU diag: begin failed (check board, power and I2C wiring)");
    return;
  }

  imuDiagnostics.whoAmI = imuDiagnostics.sensor.getChipID();
  imuDiagnostics.present = imuDiagnostics.sensor.isPresent();
  imuDiagnostics.firmwareVersion = imuDiagnostics.sensor.getFirmwareVersion();
  Serial.printf("IMU diag: addr=0x%02X whoami=0x%02X present=%d sensor_fw=0x%06lX\n",
                imuDiagnostics.address, imuDiagnostics.whoAmI,
                imuDiagnostics.present ? 1 : 0,
                static_cast<unsigned long>(imuDiagnostics.firmwareVersion));
  if (!imuDiagnostics.present) return;

  // Run the driver's electrical self-test before collecting samples.  This is
  // diagnostic evidence only; it never arms the accident state machine.
  imuDiagnostics.selfTestAttempted = true;
  imuDiagnostics.selfTestOk = imuDiagnostics.sensor.hardwareSelfTest();
  Serial.printf("IMU diag: hardware self-test=%d\n",
                imuDiagnostics.selfTestOk ? 1 : 0);

  // Use wide ranges for the first diagnostic pass.  112 Hz is sufficient to
  // inspect normal mounting motion while keeping I2C traffic bounded.  These
  // values are not accident thresholds and must not be used as such.
  const bool modeOk = imuDiagnostics.sensor.setOperationMode(OperationMode::NORMAL);
  const bool accelOk = imuDiagnostics.sensor.configAccel(
      AccelFullScaleRange::FS_16G, 112.0f, SensorQMI8658::LpfMode::MODE_0);
  const bool gyroOk = imuDiagnostics.sensor.configGyro(
      GyroFullScaleRange::FS_2000_DPS, 112.0f, SensorQMI8658::LpfMode::MODE_0);
  const bool accelEnableOk = accelOk && imuDiagnostics.sensor.enableAccel();
  const bool gyroEnableOk = gyroOk && imuDiagnostics.sensor.enableGyro();
  // A sensor that cannot pass its hardware self-test is not usable for any
  // future safety decision. Keep the diagnostic image conservative and report
  // it as unconfigured even if register writes happened to succeed.
  imuDiagnostics.configured = imuDiagnostics.selfTestOk && modeOk && accelOk &&
                              gyroOk && accelEnableOk && gyroEnableOk;
  Serial.printf("IMU diag: config normal=%d accel=%d gyro=%d enabled=%d\n",
                modeOk ? 1 : 0, accelOk ? 1 : 0, gyroOk ? 1 : 0,
                imuDiagnostics.configured ? 1 : 0);
  if (!imuDiagnostics.configured) {
    // Leave the sensor in a safe, non-sampling state when setup is incomplete.
    imuDiagnostics.sensor.disableAccel();
    imuDiagnostics.sensor.disableGyro();
  }
  Serial.printf("IMU diag: status addr=0x%02X present=%d selftest=%d configured=%d samples=%lu errors=%lu\n",
                imuDiagnostics.address,
                imuDiagnostics.present ? 1 : 0,
                imuDiagnostics.selfTestOk ? 1 : 0,
                imuDiagnostics.configured ? 1 : 0,
                static_cast<unsigned long>(imuDiagnostics.samples),
                static_cast<unsigned long>(imuDiagnostics.readErrors));
}

inline void imuDiagnosticsTick() {
  if (!imuDiagnostics.configured) return;

  const uint32_t now = millis();
  // The main loop normally runs around 10 ms.  Keep the diagnostic reader
  // non-blocking and avoid duplicate samples when the sensor is not ready.
  if (now - imuDiagnostics.lastSampleAt < 5) return;
  if (!imuDiagnostics.sensor.isDataReady(static_cast<uint8_t>(
          ImuBase::DataReadyMask::BOTH))) return;

  AccelerometerData accel;
  GyroscopeData gyro;
  const bool accelOk = imuDiagnostics.sensor.readAccel(accel);
  const bool gyroOk = imuDiagnostics.sensor.readGyro(gyro);
  if (!accelOk || !gyroOk || !isfinite(accel.mps2.x) ||
      !isfinite(accel.mps2.y) || !isfinite(accel.mps2.z) ||
      !isfinite(gyro.dps.x) || !isfinite(gyro.dps.y) ||
      !isfinite(gyro.dps.z) || !isfinite(accel.temperature)) {
    ++imuDiagnostics.readErrors;
    return;
  }

  imuDiagnostics.lastSampleAt = now;
  imuDiagnostics.ax = accel.mps2.x;
  imuDiagnostics.ay = accel.mps2.y;
  imuDiagnostics.az = accel.mps2.z;
  imuDiagnostics.gx = gyro.dps.x;
  imuDiagnostics.gy = gyro.dps.y;
  imuDiagnostics.gz = gyro.dps.z;
  imuDiagnostics.temperature = accel.temperature;
  recordImuDiagnosticSample(now, accel, gyro);
  ++imuDiagnostics.samples;

  // CSV is intentionally serial-only.  Do not add it to the BLE protocol or
  // the normal device status packets before a sampling format is reviewed.
  if (now - imuDiagnostics.lastSummaryAt >= 50) {
    imuDiagnostics.lastSummaryAt = now;
    Serial.printf("IMU,%lu,%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%.2f,%lu,%lu\n",
                  static_cast<unsigned long>(now),
                  imuDiagnostics.ax, imuDiagnostics.ay, imuDiagnostics.az,
                  imuDiagnostics.gx, imuDiagnostics.gy, imuDiagnostics.gz,
                  imuDiagnostics.temperature,
                  static_cast<unsigned long>(imuDiagnostics.samples),
                  static_cast<unsigned long>(imuDiagnostics.readErrors));
  }
}

inline void setImuDiagnosticsStream(bool enabled) {
  imuDiagnostics.streamEnabled = enabled;
  imuDiagnostics.statusPending = true;
  Serial.printf("IMU diag: BLE stream=%d\n", enabled ? 1 : 0);
}

inline void requestImuDiagnosticsStatus() {
  imuDiagnostics.statusPending = true;
  Serial.printf("IMU diag: status addr=0x%02X present=%d selftest=%d configured=%d stream=%d samples=%lu errors=%lu last=%lu\n",
                imuDiagnostics.address,
                imuDiagnostics.present ? 1 : 0,
                imuDiagnostics.selfTestOk ? 1 : 0,
                imuDiagnostics.configured ? 1 : 0,
                imuDiagnostics.streamEnabled ? 1 : 0,
                static_cast<unsigned long>(imuDiagnostics.samples),
                static_cast<unsigned long>(imuDiagnostics.readErrors),
                static_cast<unsigned long>(imuDiagnostics.lastSampleAt));
}

inline void imuDiagnosticsNotifyTick() {
  if (!imuDiagnostics.statusPending && !imuDiagnostics.streamEnabled) return;
  if (!txChar || !bleConnected || !bleAuthed) return;

  const uint32_t now = millis();
  if (imuDiagnostics.statusPending) {
    char status[128];
    const int n = snprintf(status, sizeof(status),
                           "IMU,STATUS,%02X,%d,%d,%d,%d,%lu,%lu,%lu",
                           imuDiagnostics.address,
                           imuDiagnostics.present ? 1 : 0,
                           imuDiagnostics.selfTestOk ? 1 : 0,
                           imuDiagnostics.configured ? 1 : 0,
                           imuDiagnostics.streamEnabled ? 1 : 0,
                           static_cast<unsigned long>(imuDiagnostics.samples),
                           static_cast<unsigned long>(imuDiagnostics.readErrors),
                           static_cast<unsigned long>(imuDiagnostics.lastSampleAt));
    if (n > 0) {
      txChar->setValue((uint8_t *)status, static_cast<size_t>(n));
      txChar->notify();
    }

    // Keep the legacy STATUS frame byte-for-byte compatible.  Health counters
    // are sent as a separate, versioned frame so older apps can continue to
    // parse STATUS while newer apps opt into the diagnostic summary.
    char health[192];
    const int healthN = formatImuDiagnosticHealth(health, sizeof(health));
    if (healthN > 0 && static_cast<size_t>(healthN) < sizeof(health)) {
      txChar->setValue((uint8_t *)health, static_cast<size_t>(healthN));
      txChar->notify();
      imuDiagnostics.lastHealthNotifyAt = now;
    }
    imuDiagnostics.statusPending = false;
  }

  // A stream session receives a low-rate health snapshot in addition to the
  // existing sample stream.  This is deliberately much slower than samples
  // to keep navigation notifications and the BLE connection unaffected.
  if (imuDiagnostics.streamEnabled && imuDiagnostics.samples > 0 &&
      now - imuDiagnostics.lastHealthNotifyAt >= 1000) {
    char health[192];
    const int healthN = formatImuDiagnosticHealth(health, sizeof(health));
    if (healthN > 0 && static_cast<size_t>(healthN) < sizeof(health)) {
      txChar->setValue((uint8_t *)health, static_cast<size_t>(healthN));
      txChar->notify();
      imuDiagnostics.lastHealthNotifyAt = now;
    }
  }

  // Sampling notifications are explicitly opt-in and throttled so they never
  // consume the normal navigation connection by default.
  if (!imuDiagnostics.streamEnabled || !imuDiagnostics.configured ||
      now - imuDiagnostics.lastNotifyAt < 100 || imuDiagnostics.samples == 0) return;
  imuDiagnostics.lastNotifyAt = now;
  char sample[160];
  const int n = snprintf(sample, sizeof(sample),
                         "IMU,%lu,%.3f,%.3f,%.3f,%.2f,%.2f,%.2f,%.2f",
                         static_cast<unsigned long>(now),
                         imuDiagnostics.ax, imuDiagnostics.ay, imuDiagnostics.az,
                         imuDiagnostics.gx, imuDiagnostics.gy, imuDiagnostics.gz,
                         imuDiagnostics.temperature);
  if (n > 0) {
    txChar->setValue((uint8_t *)sample, static_cast<size_t>(n));
    txChar->notify();
  }
}

#else

// Keep the call sites present in every build while compiling the diagnostic
// implementation and SensorLib interface out of the release image.
inline void initImuDiagnostics() {}
inline void imuDiagnosticsTick() {}
inline void imuDiagnosticsNotifyTick() {}
inline void setImuDiagnosticsStream(bool) {}
inline void requestImuDiagnosticsStatus() {}

#endif  // MOTONAV_IMU_DIAGNOSTICS
