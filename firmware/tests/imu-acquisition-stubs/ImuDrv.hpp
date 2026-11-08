#pragma once
#include <stdint.h>

struct SensorVector { float x, y, z; };
struct RawVector { int16_t x, y, z; };
struct AccelerometerData { SensorVector mps2{}; RawVector raw{}; float temperature = 0; };
struct GyroscopeData { SensorVector dps{}; RawVector raw{}; float temperature = 0; };
enum class OperationMode { NORMAL };
enum class AccelFullScaleRange { FS_16G };
enum class GyroFullScaleRange { FS_2000_DPS };
struct ImuBase { enum class DataReadyMask : uint8_t { BOTH }; };
static constexpr int QMI8658_L_SLAVE_ADDRESS = 0x6A, QMI8658_H_SLAVE_ADDRESS = 0x6B;

extern bool testWireLocked;
extern bool testSensorReady, testAccelOk, testGyroOk;
extern float testSensorValue;

class SensorQMI8658 {
public:
  enum class LpfMode { MODE_0 };
  template <typename T> bool begin(T &, uint8_t, int, int) { return true; }
  int getChipID() { return 5; }
  bool isPresent() { return true; }
  uint32_t getFirmwareVersion() { return 1; }
  bool hardwareSelfTest() { return true; }
  bool setOperationMode(OperationMode) { return true; }
  bool configAccel(AccelFullScaleRange, float, LpfMode) { return true; }
  bool configGyro(GyroFullScaleRange, float, LpfMode) { return true; }
  bool enableAccel() { return true; }
  bool enableGyro() { return true; }
  bool disableAccel() { return true; }
  bool disableGyro() { return true; }
  bool isDataReady(uint8_t) { assert(testWireLocked); return testSensorReady; }
  bool readAccel(AccelerometerData &value) {
    assert(testWireLocked);
    if (!testAccelOk) return false; // Deliberately leave the output untouched.
    value.mps2 = {testSensorValue, 0, 9.8f};
    value.raw = {int16_t(testSensorValue), 0, 1000};
    value.temperature = 30;
    return true;
  }
  bool readGyro(GyroscopeData &value) {
    assert(testWireLocked);
    if (!testGyroOk) return false;
    value.dps = {-7, 0, 0};
    value.raw = {-112, 0, 0};
    return true;
  }
};
