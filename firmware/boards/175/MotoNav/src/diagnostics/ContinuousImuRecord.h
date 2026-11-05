#pragma once

#include "ContinuousImuRecordCore.h"
#include <esp_heap_caps.h>
#if defined(CONFIG_NIMBLE_ENABLED)
#include "host/ble_att.h"
#endif

// All recorder state and I2C reads stay on the Arduino loop task. BLE callbacks
// only enqueue controls in the existing RX queue; they never touch this state.
static ContinuousImu::Recorder continuousImuRecorder;

inline void initContinuousImuRecord() {
  ContinuousImu::Sample *samples = nullptr;
#if MOTONAV_IMU_DIAGNOSTICS
  // Explicit PSRAM capability prevents an unavailable 128 KiB allocation from
  // silently consuming the internal memory needed by navigation and BLE.
  samples = static_cast<ContinuousImu::Sample *>(heap_caps_malloc(
      sizeof(ContinuousImu::Sample) * ContinuousImu::SAMPLE_CAPACITY,
      MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
#endif
  uint32_t boot = esp_random();
  if (!boot) boot = 1;
  continuousImuRecorder.initialize(samples, ContinuousImu::SAMPLE_CAPACITY, boot);
  Serial.printf("IMU record: target=25Hz capacity=%u allocated=%d boot=%lu\n",
      unsigned(ContinuousImu::SAMPLE_CAPACITY), samples ? 1 : 0, static_cast<unsigned long>(boot));
}

inline bool handleContinuousImuRecordControl(const uint8_t *data, int size) {
  return continuousImuRecorder.control(data, size > 0 ? size_t(size) : 0, millis());
}

inline void continuousImuRecordSample(uint32_t now, float ax, float ay, float az,
                                     float gx, float gy, float gz, float temperature) {
  continuousImuRecorder.setSensorHealthy(true);
  const ContinuousImu::Sample sample = {now, ax, ay, az, gx, gy, gz, temperature};
  continuousImuRecorder.sample(sample);
}

inline void continuousImuRecordReadError() { continuousImuRecorder.readError(); }
inline void continuousImuRecordDisconnected() { continuousImuRecorder.disconnected(); }

inline void continuousImuRecordNotifyTick() {
  static uint32_t seenConnectionEpoch = 0;
  if (seenConnectionEpoch != mediaConnectionEpoch) {
    seenConnectionEpoch = mediaConnectionEpoch;
    continuousImuRecordDisconnected();
  }
  const uint32_t now = millis();
#if MOTONAV_IMU_DIAGNOSTICS
  continuousImuRecorder.setSensorHealthy(imuDiagnostics.present && imuDiagnostics.configured &&
      imuDiagnostics.selfTestOk && imuDiagnostics.samples > 0 && now - imuDiagnostics.lastSampleAt <= 1500);
#else
  continuousImuRecorder.setSensorHealthy(false);
#endif
  if (!txChar || !bleConnected || !bleAuthed || otaActive) return;
  uint16_t mtu = 23;
#if defined(CONFIG_NIMBLE_ENABLED)
  if (bleConnHandle == 0xFFFF) return;
  const uint16_t negotiated = ble_att_mtu(bleConnHandle);
  if (negotiated >= 23) mtu = negotiated;
#endif
  uint8_t packet[ContinuousImu::MAX_FRAGMENT_SIZE];
  const size_t length = continuousImuRecorder.nextFragment(now, mtu, packet, sizeof(packet));
  if (length) {
    txChar->setValue(packet, length);
    txChar->notify();  // ACK, not notify(), decides when a sample can be freed.
  }
}
