#pragma once

// Display-only ride telemetry.  The normal firmware has no IMU and reports
// that explicitly; the opt-in diagnostic image supplies the latest QMI8658
// sample through readImuPageTelemetry().  Nothing on this page participates in
// navigation or incident decisions.

#include <math.h>
#include "../graphics/RideChineseFont.h"

static float rideVectorMagnitude(float x, float y, float z) {
  if (!isfinite(x) || !isfinite(y) || !isfinite(z)) return -1.0f;
  return sqrtf(x * x + y * y + z * z);
}
static String rideMetricValue(float value, const char *unit) {
  if (!isfinite(value) || value < 0.0f) return "--";
  return String(value, 1) + " " + unit;
}

static void drawRideMetricCn(int x, int y, int w, int h,
                             const RideCnGlyph *label, int labelCount,
                             const String &value, uint16_t color) {
  gfx->drawRoundRect(x, y, w, h, 18, 0x2104);
  drawRideCnText(label, labelCount, x + w / 2, y + 8, C_GREY);
  printCentered(value, x + w / 2, y + 37, 2, color);
}

void rideInfoPageEnter() {
  ImuPageTelemetry telemetry;
  readImuPageTelemetry(telemetry);
  if (!telemetry.streamEnabled) {
    setImuDiagnosticsStream(true);
    rideInfoStreamOwned = true;
  } else {
    rideInfoStreamOwned = false;
  }
}

void rideInfoPageLeave() {
  if (!rideInfoStreamOwned) return;
  setImuDiagnosticsStream(false);
  rideInfoStreamOwned = false;
}

void drawRideInfo() {
  onWaitScreen = false;
  frameIsMap = false;
  gfx->fillScreen(C_BLACK);
  drawStatus();
  const RideCnGlyph title[] = {RIDE_CN_0, RIDE_CN_1, RIDE_CN_2, RIDE_CN_3};
  drawRideCnText(title, 4, CX, 53, C_CYAN, 1);

  if (speedKmh >= 0) {
    printCentered(String(speedKmh), CX, 91, 5, C_WHITE);
    printCentered("km/h", CX, 140, 2, C_GREY);
  } else {
    printCentered("--", CX, 91, 5, C_GREY);
    printCentered("km/h", CX, 140, 2, C_GREY);
  }

  ImuPageTelemetry telemetry;
  const bool available = readImuPageTelemetry(telemetry);
  const uint16_t dataColor = available ? C_WHITE : C_GREY;
  const float accel = available ? rideVectorMagnitude(telemetry.ax, telemetry.ay, telemetry.az) : -1.0f;
  const float gyro = available ? rideVectorMagnitude(telemetry.gx, telemetry.gy, telemetry.gz) : -1.0f;
  float tilt = -1.0f;
  if (available && accel > 0.01f && isfinite(telemetry.az)) {
    float vertical = fabsf(telemetry.az) / accel;
    vertical = constrain(vertical, 0.0f, 1.0f);
    tilt = acosf(vertical) * 57.2957795f;
  }

  const RideCnGlyph accelLabel[] = {RIDE_CN_6, RIDE_CN_4, RIDE_CN_5};
  const RideCnGlyph gyroLabel[] = {RIDE_CN_7, RIDE_CN_4, RIDE_CN_5};
  const RideCnGlyph tiltLabel[] = {RIDE_CN_8, RIDE_CN_7};
  const RideCnGlyph tempLabel[] = {RIDE_CN_9, RIDE_CN_10};
  drawRideMetricCn(48, 186, 174, 78, accelLabel, 3, rideMetricValue(accel, "m/s2"), dataColor);
  drawRideMetricCn(258, 186, 174, 78, gyroLabel, 3, rideMetricValue(gyro, "dps"), dataColor);
  drawRideMetricCn(48, 278, 174, 78, tiltLabel, 2, rideMetricValue(tilt, "deg"), dataColor);
  drawRideMetricCn(258, 278, 174, 78, tempLabel, 2, rideMetricValue(available ? telemetry.temperature : -1.0f, "C"), dataColor);

  if (!available) {
    const RideCnGlyph unavailable[] = {RIDE_CN_21, RIDE_CN_22, RIDE_CN_23, RIDE_CN_11, RIDE_CN_12, RIDE_CN_13};
    const RideCnGlyph normalNoSensor[] = {RIDE_CN_16, RIDE_CN_17, RIDE_CN_18, RIDE_CN_19, RIDE_CN_20, RIDE_CN_21, RIDE_CN_22, RIDE_CN_23};
    drawRideCnText(unavailable, 6, CX, 378, C_ORANGE, -1);
    drawRideCnText(normalNoSensor, 8, CX, 402, C_GREY, -2);
  } else {
    const RideCnGlyph sampling[] = {RIDE_CN_24, RIDE_CN_25, RIDE_CN_26, RIDE_CN_27};
    drawRideCnText(sampling, 4, CX, 382, C_GREEN, -1);
    const RideCnGlyph sampleWord[] = {RIDE_CN_27, RIDE_CN_28};
    const RideCnGlyph errorWord[] = {RIDE_CN_29, RIDE_CN_30};
    drawRideCnText(sampleWord, 2, CX - 60, 405, C_GREY, -2);
    printCentered(String(telemetry.samples), CX - 8, 405, 1, C_GREY);
    drawRideCnText(errorWord, 2, CX + 55, 405, C_GREY, -2);
    printCentered(String(telemetry.readErrors), CX + 94, 405, 1, C_GREY);
  }
  drawPageDots();
  needFlush = true;
}

void rideInfoTick() {
  if (page != 3 || callOn || noticeKind || pairOn || powerMenuOn || fxOn || sliding) return;
  static uint32_t lastDrawAt = 0;
  const uint32_t now = millis();
  if (!rideInfoDirty && now - lastDrawAt < 250) return;
  lastDrawAt = now;
  rideInfoDirty = false;
  drawRideInfo();
}
