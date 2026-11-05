#pragma once

#include "EmergencyAlarmCore.h"
#include "../graphics/EmergencyChineseFont.h"

static EmergencyAlarm::Model emergencyAlarm;
static bool emergencyAlarmDirty = false;
static bool emergencyAlarmWasDrawn = false;

bool emergencyAlarmVisible() { return emergencyAlarm.visible(); }

void emergencyAlarmSuspendTouch() { emergencyAlarm.suspendTouch(); }

void emergencyAlarmTouch(uint8_t points, int16_t x, int16_t y, uint32_t now) {
  const uint16_t previous = emergencyAlarm.holdPermille(now);
  const bool pending = emergencyAlarm.cancelPending();
  emergencyAlarm.touch(points > 0, x, y, now);
  if (previous != emergencyAlarm.holdPermille(now) || pending != emergencyAlarm.cancelPending())
    emergencyAlarmDirty = true;
}

bool handleEmergencyAlarmCommand(const uint8_t *data, int size) {
  if (!data || size < 4 || memcmp(data, "#EA,", 4)) return false;
  uint32_t token; uint8_t state; uint16_t remaining;
  if (!EmergencyAlarm::parse(data, size_t(size), token, state, remaining)) return true;
  const uint32_t previous = emergencyAlarm.token();
  if (!emergencyAlarm.receive(token, state, remaining, millis())) return true;
  if (previous != emergencyAlarm.token()) {
    // These two slots are prompt text only, never event state or a cancel ACK.
    for (int slot = 14; slot <= 15; ++slot) {
      imgs[slot].showW = 0; imgs[slot].showH = 0; imgs[slot].recvId = -1;
    }
    if (sliding || dragging) dragAbort();
    touchHold = 0;
    musicPressed = -1;
  }
  emergencyAlarmDirty = true;
  return true;
}

void emergencyAlarmBitmapChanged() { emergencyAlarmDirty = true; }

static void drawEmergencyPromptBitmap(int slot, int top, int maxWidth, int maxHeight) {
  const TextImg &im = imgs[slot];
  if (!im.show || !im.showW || !im.showH) return;
  float scale = fminf(1.0f, fminf(float(maxWidth) / im.showW, float(maxHeight) / im.showH));
  const int width = int(im.showW * scale), height = int(im.showH * scale);
  const int left = CX - width / 2, bpr = (im.showW + 1) / 2;
  for (int y = 0; y < height; ++y) {
    const int sy = int(y / scale);
    for (int x = 0; x < width; ++x) {
      const uint8_t alpha = imgNib(im, bpr, int(x / scale), sy);
      if (alpha) gfx->drawPixel(left + x, top + y, greyColor(alpha, 255));
    }
  }
}

static void restoreAfterEmergencyAlarm() {
  callIslandDrawn = false;
  if (page == 2) drawMusic();
  else if (page == 1) drawWeather();
  else if (page == 3) drawRideInfo();
  else if (inNav) { drawNavFrame(); if (staleShown) drawStaleOverlay(); }
  else drawWaiting(shownConn ? WAIT_READY : WAIT_NOLINK);
  if (callOn) drawCallScreen();
  else if (noticeKind) drawNotificationIsland();
}

void emergencyAlarmRenderTick() {
  const uint32_t now = millis();
  emergencyAlarm.tick(now);
  const bool higherPriority = powerMenuOn || pairOn || pairReq || otaActive;
  if (higherPriority) {
    emergencyAlarm.suspendTouch();
    emergencyAlarmDirty = true;
    return;
  }
  if (!emergencyAlarm.visible()) {
    if (emergencyAlarmWasDrawn) {
      emergencyAlarmWasDrawn = false;
      restoreAfterEmergencyAlarm();
    }
    return;
  }
  if (sliding || dragging) dragAbort();
  static uint16_t lastRemaining = UINT16_MAX, lastHold = UINT16_MAX;
  static bool lastConnected = false;
  const uint16_t remaining = emergencyAlarm.remaining(now);
  const uint16_t hold = emergencyAlarm.holdPermille(now) / 20; // 50 visible progress steps.
  const bool connected = bleConnected && bleAuthed;
  if (emergencyAlarmWasDrawn && !emergencyAlarmDirty && !needFlush &&
      remaining == lastRemaining && hold == lastHold && connected == lastConnected) return;
  lastRemaining = remaining; lastHold = hold; lastConnected = connected;
  emergencyAlarmDirty = false;
  emergencyAlarmWasDrawn = true;
  onWaitScreen = false;
  frameIsMap = false;
  gfx->fillScreen(C_BLACK);
  const EmergencyAlarm::State state = emergencyAlarm.state();
  const bool countdown = state == EmergencyAlarm::COUNTDOWN;
  const bool pending = emergencyAlarm.cancelPending();
  const uint16_t accent = state == EmergencyAlarm::CANCELLED ? C_GREEN :
      state == EmergencyAlarm::SUBMITTED ? C_CYAN : C_ORANGE;
  gfx->drawCircle(CX, CX, 220, accent);
  gfx->drawCircle(CX, CX, 218, accent);
  drawEmergencyCnText("急刹报警测试", CX, 52, accent);
  // The link state does not determine whether the phone has cancelled/sent.
  aaDisc(CX - 76, 96, 4, connected ? C_GREEN : C_ORANGE);
  drawEmergencyCnText(connected ? "手机已连接" : "蓝牙已断开", CX + 10, 85, C_GREY);

  if (countdown && !pending && remaining > 0) {
    printCentered(String(remaining), CX, 121, 9, C_WHITE);
    drawEmergencyCnText("秒", CX, 197, C_GREY);
  } else {
    const char *result = pending && countdown ? "正在请求取消" :
        countdown ? "等待手机处理" : state == EmergencyAlarm::CANCELLED ? "取消成功" :
        state == EmergencyAlarm::SENDING ? "手机处理中" :
        state == EmergencyAlarm::SUBMITTED ? "短信已提交" :
        state == EmergencyAlarm::FAILED ? "短信提交失败" :
        state == EmergencyAlarm::DRILL_COMPLETE ? "演练完成" :
        state == EmergencyAlarm::INTERRUPTED ? "流程已中断" : "短信结果未知";
    drawEmergencyCnText(result, CX, 152, state == EmergencyAlarm::CANCELLED ? C_GREEN : C_WHITE);
  }

  if (countdown && !pending) {
    if (imgs[14].show && imgs[14].showW)
      drawEmergencyPromptBitmap(14, 231, 320, imgs[15].showW ? 60 : 120);
    else {
      drawEmergencyCnText("急刹测试已触发", CX, 240, C_WHITE);
      drawEmergencyCnText("无需联系时请长按取消", CX, 276, C_WHITE);
    }
    if (imgs[15].show && imgs[15].showW) drawEmergencyPromptBitmap(15, 293, 320, 60);
  } else {
    const char *detail = pending ? (connected ? "等待手机确认" : "本地取消待重连") :
        state == EmergencyAlarm::SUBMITTED ? "不代表联系人已收到" :
        state == EmergencyAlarm::FAILED ? "请在手机查看原因" :
        state == EmergencyAlarm::DRILL_COMPLETE ? "未发送短信" :
        state == EmergencyAlarm::INTERRUPTED ? "请在手机核实结果" :
        state == EmergencyAlarm::UNKNOWN ? "请在手机核实结果" :
        state == EmergencyAlarm::SENDING ? "发送请求处理中" : "";
    if (*detail) drawEmergencyCnText(detail, CX, 258, C_WHITE);
    if (pending && !countdown) drawEmergencyCnText("取消尚未确认", CX, 297, C_ORANGE);
  }

  if (countdown && !pending) {
    gfx->fillRoundRect(79, 359, 308, 59, 20, 0x2144);
    gfx->drawRoundRect(79, 359, 308, 59, 20, C_WHITE);
    drawEmergencyCnText(hold ? "保持按住" : "长按屏幕取消", CX, 372, C_WHITE);
    if (hold) gfx->fillRoundRect(97, 406, int(272 * hold / 50), 4, 2, C_CYAN);
  } else if (pending) {
    drawEmergencyCnText("取消请求尚未确认", CX, 375, C_ORANGE);
  }
  needFlush = true;
}

void emergencyAlarmNotifyTick() {
  static uint32_t seenEpoch = 0;
  if (seenEpoch != mediaConnectionEpoch) {
    seenEpoch = mediaConnectionEpoch;
    emergencyAlarm.disconnected();
  }
  if (!txChar || !bleConnected || !bleAuthed || otaActive) return;
  char request[20];
  const size_t size = emergencyAlarm.cancelNotification(millis(), request, sizeof(request));
  if (size) {
    txChar->setValue(reinterpret_cast<uint8_t *>(request), size);
    txChar->notify();
  }
}
