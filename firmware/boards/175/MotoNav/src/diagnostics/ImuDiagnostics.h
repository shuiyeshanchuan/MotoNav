#pragma once

// QMI8658 diagnostics are deliberately opt-in.  The normal navigation image
// must not touch the IMU until the board wiring has been verified on a real
// 1.75C.  Build a diagnostic image with -DMOTONAV_IMU_DIAGNOSTICS=1.
#ifndef MOTONAV_IMU_DIAGNOSTICS
#define MOTONAV_IMU_DIAGNOSTICS 0
#endif

#if MOTONAV_IMU_DIAGNOSTICS

#include <ImuDrv.hpp>

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
  bool streamEnabled = false;
  bool statusPending = false;
  float ax = NAN, ay = NAN, az = NAN;
  float gx = NAN, gy = NAN, gz = NAN;
  float temperature = NAN;
};

ImuDiagnosticsState imuDiagnostics;

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
  imuDiagnostics.lastSampleAt = now;
  if (!accelOk || !gyroOk) {
    ++imuDiagnostics.readErrors;
    return;
  }

  imuDiagnostics.ax = accel.mps2.x;
  imuDiagnostics.ay = accel.mps2.y;
  imuDiagnostics.az = accel.mps2.z;
  imuDiagnostics.gx = gyro.dps.x;
  imuDiagnostics.gy = gyro.dps.y;
  imuDiagnostics.gz = gyro.dps.z;
  imuDiagnostics.temperature = accel.temperature;
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
    imuDiagnostics.statusPending = false;
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
