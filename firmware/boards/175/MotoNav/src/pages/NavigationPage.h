#pragma once
// 进度环动画和导航页面。由 MotoNav.ino 按依赖顺序包含，仅编译一次。
#include "../../TurnRingMotion.h"
TurnRingMotion turnRingMotion;
uint32_t ringAnimationDrawAt = 0;
uint32_t arrowAnimationAt = 0;
unsigned long mapWaitAt = 0;
bool mapWaitExpired = false;
bool mapUnavailableShown = false;
#define NAV_MAP_WAIT_MS 2500UL
#define CRUISE_MAP_WAIT_MS 15000UL
void updateRingTarget() {
  bool hasRing = (navDir != "S") && navDist <= STAGE_FAR_M;
  float target = hasRing ? (float)(STAGE_FAR_M - navDist) / STAGE_FAR_M : 0;
  const uint32_t now = millis();
  if (navDir != ringDir) arrowAnimationAt = now ? now : 1;
  turnRingMotion.update(ringShown, target, navDir != ringDir || !ringActive || !hasRing, now);
  ringShown = turnRingMotion.value(now);
  ringTarget = target;
  ringActive = hasRing;
  ringDir = navDir;
}

void animateRing() {
  if (!inNav || staleShown || rerouteAt) return;
  const uint32_t now = millis();
  if (now - ringAnimationDrawAt < 33) return;
  ringAnimationDrawAt = now;
  if (ringActive) {
    const float next = turnRingMotion.value(now);
    if (next != ringShown) { ringShown = next; navDirty = true; }
  }
  if (arrowAnimationAt) {
    if (now - arrowAnimationAt < 180) navDirty = true;
    else arrowAnimationAt = 0;
  }
}

// 按当前全部状态画一整帧导航画面
void drawNavFrame() {
  onWaitScreen = false;                                 // v3.6 离开首页，猫猫不再动
  if (mapModeActive()) {
    mapWaitAt = 0; mapWaitExpired = false; mapUnavailableShown = false;
    drawMapFrame();
    drawPageDots();
    return;
  }
  // After a fresh connection, navigation text can arrive before the map block.
  // Give map capture and BLE transfer time before showing the fallback page.
  if (mapOn && styleIdx() != ST_DRAGON && !mapWaitExpired) {
    if (!mapWaitAt) mapWaitAt = millis();
    if (millis() - mapWaitAt < (cruiseOn ? CRUISE_MAP_WAIT_MS : NAV_MAP_WAIT_MS)) {
      frameIsMap = false;
      gfx->fillScreen(C_BLACK);
      drawStatus();
      printCentered("LOADING MAP", CX, 205, 3, C_CYAN);
      if (cruiseOn) printCentered("CRUISE", CX, 262, 4, C_WHITE);
      else printCentered(distText(navDist), CX, 262, 4, C_WHITE);
      if (gpsWeak) printCentered(posAt ? "POSITION APPROX" : "WAIT GPS", CX, 326, 2, C_ORANGE);
      drawPageDots();
      needFlush = true;
      return;
    }
    mapWaitExpired = true; // Keep turn instructions visible while the map is still loading.
  }
  if (!mapOn) { mapWaitAt = 0; mapWaitExpired = false; mapUnavailableShown = false; }
  viewInit = false;
  if (cruiseOn) {                                       // v4.8 巡航但还没有地图：只显示车速和测速
    gfx->fillScreen(C_BLACK);
    bool over = hasCam && camLimit > 0 && speedKmh > camLimit;
    if (over) fillRingBand(0, 1, 228, 233, C_RED);
    drawStatus();
    drawSpeedBlock(over);
    printCentered("CRUISE", CX, 300, 4, C_CYAN);
    printCentered(!mapOn ? "MAP OFF" : !posAt ? "WAIT GPS" : "MAP UNAVAILABLE",
                  CX, 350, 2, C_ORANGE);
    drawPageDots();
    needFlush = true;
    return;
  }
  gfx->fillScreen(C_BLACK);
  bool isS = (navDir == "S");
  bool isFar = !isS && navDist > STAGE_FAR_M;
  bool lanes = laneN > 0 && (ringActive || navFocus == 4);
  bool over = hasCam && camLimit > 0 && speedKmh > camLimit;
  float laneK = lanes ? 0.85f : 1.0f;
  float iconCy = lanes ? ICON_CY_LANE : ICON_CY;
  if (arrowAnimationAt) {
    float t = fminf(1.0f, (millis() - arrowAnimationAt) / 180.0f);
    laneK *= 0.92f + 0.08f * t * t * (3 - 2 * t);
  }

  // 边缘：进度环 / 超速红边
  float tp = ringActive ? -1 : tripFrac();              // v7.4 平时外圈是行程环，转弯倒计时时让位
  if (tp >= 0) drawTripRing(tp);
  else if (!isS) drawRingTrack();
  if (ringActive) {
    bool isNear = navDist <= STAGE_NEAR_M;
    drawRingProgress(ringShown, isNear ? 14 : 10,
                     isNear ? C_ORANGE : navLightMode == 2 ? C_WHITE : C_YELLOW);
  }
  if (over) fillRingBand(0, 1, 228, 233, C_RED);

  drawStatus();
  drawSpeedBlock(over);

  // 中间图标
  // v1.8.2 图标可用区域：车速下方 ~ 车道（或距离数字）上方，各留 8 像素
  float iconTop = ICON_TOP, iconBot = (lanes ? LANES_Y : DIST_Y) - 8;
  if (isFar)     drawPreview(navDir, iconCy, 0.66f * laneK, iconTop, iconBot);
  else if (isS)  drawBigIcon("S", C_WHITE, iconCy, laneK, iconTop, iconBot);
  else           drawBigIcon(navDir, navDist <= STAGE_NEAR_M ? C_ORANGE : C_WHITE, iconCy, laneK, iconTop, iconBot);

  // 车道、距离、路名、底部信息
  if (lanes) drawLanes(LANES_Y);
  int distSize = lanes ? 6 : 7;
  if (navLargeText || navFocus == 2) distSize = min(distSize + 1, 8);
  if (navFocus == 1) distSize = max(5, distSize - 1);
  printCentered(distText(navDist), CX, lanes ? DIST_Y_LANE : DIST_Y, distSize,
                navLightMode == 2 ? C_YELLOW : C_WHITE);
  if (navFocus == 3) gfx->fillRect(CX - 90, ROAD_Y - 7, 180, 3, C_CYAN);
  drawTextImg(0, lanes ? ROAD_Y_LANE : ROAD_Y, navFocus == 3 || navLightMode == 2 ? 0xFF : 0xDD);
  drawTextImg(1, BOTTOM_Y, navLightMode == 2 || navFocus == 5 ? 0xDD : 0x99);
  drawNavHints(128);                                    // v5.0
  drawRouteMeta(402);
  if (mapOn && styleIdx() != ST_DRAGON)
    printCentered(!posAt ? "WAIT GPS" : mapUnavailableShown ? "MAP UNAVAILABLE" : "MAP LOADING",
                  CX, 427, 1, C_ORANGE);
  drawPageDots();
  needFlush = true;
}
