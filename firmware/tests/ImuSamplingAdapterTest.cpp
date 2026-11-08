#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

#define MOTONAV_IMU_DIAGNOSTICS 1
static uint32_t testNow = 0;
uint32_t millis() { return testNow; }
using TickType_t = uint32_t;
using TaskHandle_t = void *;
static constexpr int pdFALSE = 0, pdPASS = 1;
static void *sharedWireMutex = reinterpret_cast<void *>(1);
static int testTaskCore = -1, testTaskPriority = -1;
static bool testTaskAllocationOk = true;
int xTaskCreatePinnedToCore(void (*)(void *), const char *, unsigned stack, void *,
                           int priority, TaskHandle_t *task, int core) {
  assert(stack == 4096);
  testTaskCore = core; testTaskPriority = priority;
  if (!testTaskAllocationOk) return 0;
  *task = reinterpret_cast<void *>(1);
  return pdPASS;
}
TickType_t xTaskGetTickCount() { return millis(); }
TickType_t pdMS_TO_TICKS(uint32_t ms) { return ms; }
int xTaskDelayUntil(TickType_t *, TickType_t) { return 1; }
void vTaskDelay(TickType_t) {}
using portMUX_TYPE = std::mutex;
#define portMUX_INITIALIZER_UNLOCKED {}
#define portENTER_CRITICAL(mux) (mux)->lock()
#define portEXIT_CRITICAL(mux) (mux)->unlock()
bool testWireLocked = false;
class SharedWireGuard {
public:
  SharedWireGuard() { assert(!testWireLocked); testWireLocked = true; }
  ~SharedWireGuard() { assert(testWireLocked); testWireLocked = false; }
};
bool testSensorReady = true, testAccelOk = true, testGyroOk = true;
float testSensorValue = 0;
static std::function<void()> testOnSerial;
static unsigned testNvsWrites = 0;
struct SerialStub {
  template <typename... Args> void printf(const char *, Args...) {
    assert(!testWireLocked);
    if (testOnSerial) testOnSerial();
  }
  void println(const char *) { assert(!testWireLocked); }
} Serial;
struct Preferences {
  bool begin(const char *, bool readOnly) { return !readOnly; }
  size_t getBytesLength(const char *) { return 0; }
  size_t getBytes(const char *, void *, size_t) { return 0; }
  size_t putBytes(const char *, const void *, size_t size) {
    assert(!testWireLocked); ++testNvsWrites; return size;
  }
  void end() {}
};
static int Wire = 0, speedKmh = 0;
static constexpr int IIC_SDA = 1, IIC_SCL = 2;
struct TxStub {
  void setValue(uint8_t *, size_t) { assert(!testWireLocked); }
  void notify() { assert(!testWireLocked); }
};
static TxStub *txChar = nullptr;
static bool bleConnected = false, bleAuthed = false, otaActive = false;
static uint32_t mediaConnectionEpoch = 0;
uint32_t esp_random() { return 123; }
void continuousImuRecordReadError(uint32_t count = 1);
void continuousImuRecordAcquisitionOverflow(uint32_t count);
void continuousImuRecordSample(uint32_t, float, float, float, float, float, float, float);
#include "../boards/175/MotoNav/src/diagnostics/ImuDiagnostics.h"
#include "../boards/175/MotoNav/src/diagnostics/ContinuousImuRecord.h"

static ContinuousImu::Sample testStorage[4096] = {};
static void reset() {
  assert(!testWireLocked);
  imuDiagnostics = ImuDiagnosticsState();
  imuDiagnostics.present = imuDiagnostics.configured = imuDiagnostics.selfTestOk = true;
  imuAcquisitionQueue = ImuAcquisition::Queue<IMU_ACQUISITION_CAPACITY>();
  imuAcquisitionSeenDrops = 0;
  imuSamplingTask = nullptr;
  imuMountCollector = ImuMount::Collector();
  imuMountReference = {};
  continuousImuRecorder = ContinuousImu::Recorder();
  continuousImuRecorder.initialize(testStorage, 4096, 1);
  continuousImuRecorder.setSensorHealthy(true);
  testSensorReady = testAccelOk = testGyroOk = true;
  sharedWireMutex = reinterpret_cast<void *>(1);
  testOnSerial = {};
  testSensorValue = 0;
  testNvsWrites = 0;
  testNow = 0;
}
static void capture(uint32_t now, float value = 0) {
  testNow = now; testSensorValue = value;
  acquireImuSample();
  assert(!testWireLocked);
}
static void issue(uint8_t opcode, uint32_t session, uint32_t now) {
  testNow = now;
  uint8_t command[18] = {0xD7, 0x43, 1, opcode};
  ContinuousImu::write32(command + 4, session);
  ContinuousImu::write32(command + 8, 1);
  ContinuousImu::write16(command + 16, ContinuousImu::crc16(command, 16));
  assert(handleContinuousImuRecordControl(command, sizeof(command)));
}

static void testRealAdapterBacklogAndHealth() {
  reset(); issue(1, 1, 0);
  for (uint32_t now = 0; now <= 1000; now += 10) capture(now, float(now));
  testNow = 1001;
  imuDiagnosticsTick();
  assert(imuDiagnostics.samples == 101 && imuDiagnostics.sampleGaps == 0);
  assert(continuousImuRecorder.nextSeq() == 26 && continuousImuRecorder.missedSlots() == 0);
  assert(testStorage[25].timestamp == 1000 && testStorage[25].ax == 1000);
  assert(imuDiagnostics.lastSampleAt == 1000);
  ImuPageTelemetry page;
  assert(readImuPageTelemetry(page) && page.gx == -7);
  testNow = 2501;
  assert(!readImuPageTelemetry(page));
}

static void testControlFencesAndInvalidControls() {
  reset();
  capture(50, 1); capture(90, 2);
  // This physical sample arrives while the START adapter drains its old prefix.
  bool fired = false;
  testOnSerial = [&] { if (!fired) { fired = true; capture(101, 3); } };
  issue(1, 7, 100);
  testOnSerial = {};
  assert(continuousImuRecorder.nextSeq() == 0);
  imuDiagnosticsTick();
  assert(continuousImuRecorder.nextSeq() == 1 && testStorage[0].timestamp == 101);
  capture(151, 4); capture(191, 5);
  fired = false;
  testOnSerial = [&] { if (!fired) { fired = true; capture(201, 6); } };
  issue(2, 7, 200);
  testOnSerial = {};
  const uint32_t count = continuousImuRecorder.nextSeq();
  imuDiagnosticsTick();
  assert(continuousImuRecorder.nextSeq() == count && !continuousImuRecorder.active());
  capture(210, 7);
  const auto queued = imuAcquisitionQueue.snapshot().count;
  const uint8_t ordinary[] = {'N', ',', 'X'};
  assert(!handleContinuousImuRecordControl(ordinary, sizeof(ordinary)));
  const uint8_t corrupt[18] = {0xD7, 0x43, 1, 1};
  assert(handleContinuousImuRecordControl(corrupt, sizeof(corrupt)));
  assert(imuAcquisitionQueue.snapshot().count == queued);
}

static void testSameMillisAndClockWrap() {
  reset();
  capture(UINT32_MAX - 9, 1);
  issue(1, 7, UINT32_MAX - 9); // Existing same-ms sample is outside START.
  capture(UINT32_MAX - 9, 2);
  capture(30, 3);
  issue(2, 7, 30); // Capture before the STOP fence is included even in same ms.
  assert(continuousImuRecorder.nextSeq() == 2 && continuousImuRecorder.missedSlots() == 0);
  assert(testStorage[0].ax == 2 && testStorage[1].timestamp == 30);
}

static void testErrorsOverflowAndMountOwnership() {
  reset();
  capture(0);
  testNow = 1; startImuMountReference();
  assert(imuMountCollector.state == ImuMount::COLLECTING && imuMountCollector.count == 0);
  testSensorReady = false; capture(5);
  assert(imuAcquisitionQueue.snapshot().count == 0);
  testSensorReady = true;
  issue(1, 7, 10);
  for (uint32_t i = 1; i <= 300; ++i) capture(i * 10);
  testAccelOk = false; capture(3010);
  testNow = 3011; imuDiagnosticsTick();
  assert(continuousImuRecorder.overflow() == 45);
  assert(continuousImuRecorder.readErrors() == 1 && imuDiagnostics.readErrors == 1);
  assert(imuMountCollector.state == ImuMount::FAILED);
  testAccelOk = true; capture(3020);
  imuDiagnosticsTick();
  assert(continuousImuRecorder.missedSlots() > 0); // Queue loss is not concealed.
  reset();
  startImuSamplingTask();
  assert(imuSamplingTask && testTaskCore == 1 && testTaskPriority == 2);
  reset(); sharedWireMutex = nullptr;
  startImuSamplingTask();
  assert(!imuSamplingTask && !imuDiagnostics.configured);
}

static void testMountCaptureUsesOriginalNativeSamples() {
  reset(); capture(0);
  testNow = 1; startImuMountReference();
  for (uint32_t at = 10; at <= 5020; at += 10) {
    capture(at);
    if (at % 250 == 0) imuDiagnosticsTick();
  }
  imuDiagnosticsTick();
  assert(imuMountCollector.state == ImuMount::READY);
  assert(imuMountCollector.count >= ImuMount::MIN_SAMPLES);
  assert(testNvsWrites == 1 && ImuMount::referenceValid(imuMountReference));
  assert(std::fabs(imuMountReference.az - 9.8f) < 0.001f);
  assert(imuDiagnostics.gx == -7.0f && imuMountCollector.gyro[0].mean == -7.0f);
}

int main() {
  testRealAdapterBacklogAndHealth();
  testControlFencesAndInvalidControls();
  testSameMillisAndClockWrap();
  testErrorsOverflowAndMountOwnership();
  testMountCaptureUsesOriginalNativeSamples();
  std::printf("PASS: real IMU adapter, backlog/health, START/STOP fences, same-ms/rollover, errors, overflow, mount and task gates; event=%zu queue=%zu\n",
      sizeof(ImuAcquisition::Event), sizeof(imuAcquisitionQueue));
}
