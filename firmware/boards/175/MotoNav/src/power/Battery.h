#pragma once
// 电源芯片读取及外接电源检测。由 MotoNav.ino 按依赖顺序包含，仅编译一次。
// ================= v6.8 电池（AXP2101 电源芯片，和触摸屏共用 I2C，地址 0x34）=================
#define PMU_ADDR 0x34
bool pmuOK = false;
int batPct = -1;                       // -1 = 没接电池
bool batChg = false, usbIn = false;
int batMv = 0;
int vbusMv = 0;
int pmuTempC10 = -1;                   // AXP2101 die temperature, 0.1 °C; -1 = unavailable
int batteryLevel = 0;                  // 0 unknown, 1 critical, 2 low, 3 normal
unsigned long batAt = 0, batSentAt = 0;
String pendingBat = "";

enum {
  POWER_LEVEL_UNKNOWN = 0,
  POWER_LEVEL_CRITICAL = 1,
  POWER_LEVEL_LOW = 2,
  POWER_LEVEL_NORMAL = 3
};

int classifyBatteryLevel(int pct) {
  if (pct < 0 || pct > 100) return POWER_LEVEL_UNKNOWN;
  if (pct <= 5) return POWER_LEVEL_CRITICAL;
  if (pct <= 20) return POWER_LEVEL_LOW;
  return POWER_LEVEL_NORMAL;
}

bool pmuTemperatureKnown() {
  return pmuTempC10 >= -400 && pmuTempC10 <= 1250;
}

// OTA is intentionally conservative: external power is sufficient, otherwise
// require a usable battery reserve. Unknown PMU/temperature readings reject OTA.
bool powerAllowsOta() {
  if (!pmuOK || !pmuTemperatureKnown() || pmuTempC10 > 700) return false;
  return usbIn || batPct >= 40;
}

int pmuRead(uint8_t reg) {
  Wire.beginTransmission(PMU_ADDR);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return -1;
  if (Wire.requestFrom((uint8_t)PMU_ADDR, (uint8_t)1) != 1) return -1;
  return Wire.read();
}
void pmuSetBit(uint8_t reg, uint8_t bit) {
  int v = pmuRead(reg);
  if (v < 0 || (v & (1 << bit))) return;
  Wire.beginTransmission(PMU_ADDR);
  Wire.write(reg);
  Wire.write((uint8_t)(v | (1 << bit)));
  Wire.endTransmission();
}

// 开机时调一次：只打开电量计、电池检测、电池电压测量，不动任何供电设置
void batInit() {
  pmuOK = pmuRead(0x00) >= 0;
  if (!pmuOK) return;
  pmuSetBit(0x18, 3);                  // 电量计
  pmuSetBit(0x68, 0);                  // 电池检测
  pmuSetBit(0x30, 0);                  // 电池电压测量
  pmuSetBit(0x30, 4);                  // AXP2101 die temperature ADC
}

int pmuRead14(uint8_t highReg) {
  const int high = pmuRead(highReg);
  const int low = pmuRead((uint8_t)(highReg + 1));
  if (high < 0 || low < 0) return -1;
  return ((high & 0x3F) << 8) | low;
}

int pmuReadTemperatureC10() {
  const int raw = pmuRead14(0x3C);
  if (raw < 0) return -1;
  // AXP2101: T(°C) = 22 + (7274 - raw14) / 20.
  return 220 + (7274 - raw) / 2;
}

// AXP2101 STATUS1 bit 5 is VBUS-good, independent of charging/full state.
// Unknown/stale power readings keep the device awake, rather than guessing battery.
void idlePowerPollSupply(bool force = false) {
  const uint32_t now = millis();
  if (!force && idlePowerSupply.sampled &&
      (uint32_t)(now - idlePowerSupply.checkedAt) < 500) return;
  const bool wasBattery = idlePowerSupplyAllowsOff(&idlePowerSupply, now);
  const int status = pmuOK ? pmuRead(0x00) : -1;
  idlePowerSupplyUpdate(&idlePowerSupply, now, status);
  // Unplugging / recovering a valid battery-only reading starts a full interval.
  if (!wasBattery && idlePowerSupplyAllowsOff(&idlePowerSupply, now))
    idlePowerOffReset(&idlePowerOffTimer, now);
}

// 小电池图标：x0,y0 = 左上角（电池身 24x12，右边小凸头，再右边是百分比数字）
