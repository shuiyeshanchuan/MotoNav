#pragma once

// Display-only ride telemetry.  The normal firmware has no IMU and reports
// that explicitly; the opt-in diagnostic image supplies the latest QMI8658
// sample through readImuPageTelemetry().  Nothing on this page participates in
// navigation or incident decisions.

#include <math.h>

static float rideVectorMagnitude(float x, float y, float z) {
  if (!isfinite(x) || !isfinite(y) || !isfinite(z)) return -1.0f;
  return sqrtf(x * x + y * y + z * z);
}
static String rideMetricValue(float value, const char *unit) {
  if (!isfinite(value) || value < 0.0f) return "--";
  return String(value, 1) + " " + unit;
}

static void drawRideMetric(int x, int y, int w, int h,
                           const char *label, const String &value,
                           uint16_t color) {
  gfx->drawRoundRect(x, y, w, h, 18, 0x2104);
  printCentered(label, x + w / 2, y + 10, 1, C_GREY);
  printCentered(value, x + w / 2, y + 34, 2, color);
}

void drawRideInfo() {
  onWaitScreen = false;
  frameIsMap = false;
  gfx->fillScreen(C_BLACK);
  drawStatus();
  printCentered("RIDE INFO", CX, 56, 3, C_CYAN);

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

  drawRideMetric(48, 186, 174, 78, "ACCEL", rideMetricValue(accel, "m/s2"), dataColor);
  drawRideMetric(258, 186, 174, 78, "GYRO", rideMetricValue(gyro, "dps"), dataColor);
  drawRideMetric(48, 278, 174, 78, "TILT EST", rideMetricValue(tilt, "deg"), dataColor);
  drawRideMetric(258, 278, 174, 78, "TEMP", rideMetricValue(available ? telemetry.temperature : -1.0f, "C"), dataColor);

  if (!available) {
    printCentered("IMU OFF / WAITING", CX, 382, 2, C_ORANGE);
    printCentered("NORMAL BUILD HAS NO SENSOR", CX, 405, 1, C_GREY);
  } else {
    printCentered(telemetry.streamEnabled ? "QMI8658 LIVE / STREAM" : "QMI8658 LIVE", CX, 390, 1, C_GREEN);
    printCentered(String("SAMPLES ") + telemetry.samples + "  ERR " + telemetry.readErrors,
                 CX, 407, 1, C_GREY);
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
