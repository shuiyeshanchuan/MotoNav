#pragma once
// 触摸初始化与来电页面。由 MotoNav.ino 按依赖顺序包含，仅编译一次。
// 来电与微信通知显示在当前导航、天气或音乐画面的顶部。
// 电话使用图片栏 2 显示联系人，按住底部小卡片接听；结束后恢复当前页面。
bool callOn = false;
String callNum = "";
unsigned long callAt = 0, callSeenAt = 0;
bool callIslandDrawn = false;
// #I,kind,ttl,canAnswer: 1=phone, 2=WeChat call, 3=WeChat message.
uint8_t noticeKind = 0;
unsigned long noticeAt = 0, noticeUntil = 0;
bool noticeDirty = false;
bool noticeCanAnswer = false;

// v5.2 触屏接电话：按住任意位置 HOLD_MS 毫秒 → 发 "T,A" 给手机（v6.9 800 → 200）
#define HOLD_MS 200
#define WECHAT_HOLD_MS 500
TouchDrvCST92xx touch;
bool touchOK = false;
unsigned long touchHoldAt = 0, touchLastSeen = 0;
float touchHold = 0;                 // 0~1 按住的进度
bool callAnswerSent = false;
unsigned long callAnswerAt = 0;
String pendingProf = "";             // v5.8 分步用时
String pendingEvt = "";              // 待发给手机的触摸事件

// 1.75C PWR key: show a software menu after 2 seconds. The PMIC still has its
// hardware long-press cutoff, so the user must release PWR before tapping.
#define PWR_HOLD_MENU_MS 2000UL
bool powerButtonDown = false;
unsigned long powerButtonAt = 0;
unsigned long powerButtonReleaseAt = 0;
bool powerMenuOn = false;
bool powerMenuDismissedForHold = false;
bool powerMenuNeedsTouchRelease = false;

// v5.3 触摸芯片一有动静就拉低中断脚；平时只看这个标记，不用一直读 I2C
volatile bool touchIrq = false;
void IRAM_ATTR onTouchIrq() { touchIrq = true; }

void touchInit() {
  touch.setPins(TP_RESET, TP_INT);
  touchOK = touch.begin(Wire, 0x5A, IIC_SDA, IIC_SCL);
  Serial.println(touchOK ? "touch ok" : "touch not found");
  if (touchOK) attachInterrupt(TP_INT, onTouchIrq, FALLING);   // v5.3
}

void powerButtonInit() {
  // SYS_OUT is driven by the PMIC and reads high while PWR is pressed.
  pinMode(PWR_BUTTON_PIN, INPUT);
}

void drawCallScreen();

static void restoreAfterPowerMenu() {
  if (callOn) { callIslandDrawn = false; drawCallScreen(); }
  else if (pairOn) drawPairScreen();
  else if (page == 2) musicDirty = true;
  else if (page == 1) wxDirty = true;
  else if (inNav) navDirty = true;
  else drawWaiting(shownConn ? WAIT_READY : WAIT_NOLINK);
}

static void dismissPowerMenu() {
  // A cancelled menu is a completed interaction.  Clear the menu state so the
  // next independent PWR hold can open it again after the normal 2 s hold.
  powerMenuOn = false;
  powerMenuDismissedForHold = powerButtonDown;
  restoreAfterPowerMenu();
}

void drawPowerMenu() {
  onWaitScreen = false;
  frameIsMap = false;
  gfx->fillScreen(C_BLACK);
  printCentered("POWER MENU", CX, 76, 3, C_WHITE);
  printCentered("RELEASE PWR BUTTON", CX, 124, 2, C_GREY);

  gfx->fillRoundRect(38, 190, 184, 104, 18, 0xC800);
  gfx->drawRoundRect(38, 190, 184, 104, 18, C_RED);
  printCentered("POWER OFF", 130, 231, 2, C_WHITE);

  gfx->fillRoundRect(244, 190, 184, 104, 18, 0x04A0);
  gfx->drawRoundRect(244, 190, 184, 104, 18, C_GREEN);
  printCentered("RESTART", 336, 231, 2, C_WHITE);
  printCentered("TAP AN ACTION", CX, 356, 2, C_DIM);
  needFlush = true;
}

void powerButtonPoll() {
  const unsigned long now = millis();
  const bool pressed = digitalRead(PWR_BUTTON_PIN) == HIGH;
  if (!pressed) {
    if (!powerButtonDown) return;
    if (!powerButtonReleaseAt) powerButtonReleaseAt = now;
    if (now - powerButtonReleaseAt < 80) return;  // Ignore short GPIO3 dips while PWR is held.
    powerButtonDown = false;
    powerButtonAt = 0;
    powerMenuDismissedForHold = false;
    return;
  }
  powerButtonReleaseAt = 0;
  if (!powerButtonDown) {
    powerButtonDown = true;
    powerButtonAt = now;
  }
  if (!powerMenuOn && !powerMenuDismissedForHold && now - powerButtonAt >= PWR_HOLD_MENU_MS) {
    powerMenuOn = true;
    powerMenuNeedsTouchRelease = true;
    drawPowerMenu();
  }
}

bool touchDown = false;
int16_t touchX0 = 0, touchY0 = 0, touchX1 = 0, touchY1 = 0;
void dragMove(int dx, int dy, unsigned long now);   // v5.5 跟手换页
void touchVectorToCanvas(float &dx, float &dy);
int musicButtonAt(int x, int y);
void musicTap(int x, int y);
void dragEnd();
void dragAbort();

// 读触摸：按下 → 报坐标；来电时按住够久 → 接听；拖动 → 跟手换页（v5.5）
// 手指离开超过 0.15 秒才算松开（防止读数偶尔丢一下）
void touchPoll() {
  static unsigned long lastPoll = 0;
  unsigned long now = millis();
  if (!touchOK) return;
  if (!touchIrq && !touchDown && !callOn && noticeKind != 2 && !powerMenuOn) return;
  if (now - lastPoll < 12) return;
  lastPoll = now;
  touchIrq = false;
  if (callAnswerSent && now - callAnswerAt > 4000) {
    callAnswerSent = false;   // 4 秒还在响：允许再按一次
    if (noticeKind == 2) noticeDirty = true;
  }
  int16_t x[5] = {0}, y[5] = {0};
  uint8_t n = touch.getPoint(x, y, 1);
  if (n == 0 && (callOn || noticeKind == 2)) {
    if (noticeKind == 2 && touchHold > 0) noticeDirty = true;
    touchHoldAt = 0;
  }
  if (powerMenuOn) {
    // A touch that began on the previous page must end before it can select a menu action.
    if (powerButtonDown || powerMenuNeedsTouchRelease) {
      if (n == 0) { touchDown = false; powerMenuNeedsTouchRelease = false; }
      return;
    }
    if (n > 0) {
      if (!touchDown) {
        touchDown = true;
        touchX0 = touchX1 = x[0];
        touchY0 = touchY1 = y[0];
      }
      touchX1 = x[0]; touchY1 = y[0]; touchLastSeen = now;
    } else if (touchDown && now - touchLastSeen > 80) {
      const bool tap = abs(touchX1 - touchX0) < 36 && abs(touchY1 - touchY0) < 36;
      const int x0 = touchX0, y0 = touchY0;
      touchDown = false;
      // Touch X is mirrored on this panel: visual left reports the right range.
      if (tap && y0 >= 190 && y0 < 294 && x0 >= 38 && x0 < 222) {
        powerMenuOn = false;
        powerButtonDown = false;
        gfx->fillScreen(C_BLACK);
        printCentered("RESTARTING", CX, 220, 3, C_GREEN);
        needFlush = true;
        flushSync();
        delay(120);
        ESP.restart();
      }
      if (tap && y0 >= 190 && y0 < 294 && x0 >= 244 && x0 < 428) {
        powerMenuOn = false;
        powerButtonDown = false;
        idlePowerOffShutdown();
      }
      if (powerMenuOn && !tap) dismissPowerMenu();
      if (powerMenuOn && tap && !(y0 >= 190 && y0 < 294 &&
          ((x0 >= 38 && x0 < 222) || (x0 >= 244 && x0 < 428)))) dismissPowerMenu();
      if (!powerMenuOn) touchEat = false;
    }
    return;
  }
  if (n > 0) {
    if (touchDown && touchHoldAt == 0 && (callOn || noticeKind == 2)) {
      // A missing sample breaks the hold, even inside the release debounce window.
      touchHoldAt = now;
      touchX0 = x[0]; touchY0 = y[0];
    }
    if (!touchDown) {
      dragAbort(); // A new touch starts a fresh gesture, even after a menu interrupted the last one.
      touchDown = true;
      touchHoldAt = now;
      touchX0 = touchX1 = x[0]; touchY0 = touchY1 = y[0];
      pendingEvt = "T,P," + String(x[0]) + "," + String(y[0]);
      if (page == 2 && !callOn && noticeKind != 2 && !pairOn) { musicPressed = musicButtonAt(x[0], y[0]); musicDirty = true; }
      if (fxOn && !callOn) { stopArriveFx(); touchEat = true; }          // v7.1 点一下结束到达特效
    }
    touchX1 = x[0]; touchY1 = y[0];
    touchLastSeen = now;
    if (callOn || noticeKind == 2 || pairOn || touchEat || fxOn) dragAbort();
    else dragMove(touchX1 - touchX0, touchY1 - touchY0, now);
  } else if (touchDown && now - touchLastSeen > 80) {
    touchDown = false;
    touchHoldAt = 0;
    if (callOn || noticeKind == 2 || pairOn || touchEat || fxOn) dragAbort();
    else {
      if (page == 2 && abs(touchX1 - touchX0) < 36 && abs(touchY1 - touchY0) < 36) musicTap(touchX0, touchY0);
      if (page == 1 && abs(touchX1 - touchX0) < 36 && abs(touchY1 - touchY0) < 36) weatherTap();
      dragEnd();
    }
    touchEat = false;
    if (musicPressed >= 0) { musicPressed = -1; musicDirty = true; }
  }
  // 来电：按住不动才算（滑动不算）
  bool still = abs(touchX1 - touchX0) < 60 && abs(touchY1 - touchY0) < 60;
  float islandX = touchX0 - CX, islandY = touchY0 - CX;
  touchVectorToCanvas(islandX, islandY);
  islandX += CX; islandY += CX;
  const bool wechatAnswer = noticeKind == 2 && noticeCanAnswer &&
      (int32_t)(noticeUntil - now) > 0 && (int32_t)(touchHoldAt - noticeAt) >= 0 &&
      islandX >= 94 && islandX < 386 && islandY >= 49 && islandY < 155;
  const int oldHoldWidth = (int)(touchHold * 160);
  touchHold = ((callOn || wechatAnswer) && n > 0 && touchDown && still) ?
      min(1.0f, (now - touchHoldAt) / (float)(callOn ? HOLD_MS : WECHAT_HOLD_MS)) : 0;
  if (wechatAnswer && oldHoldWidth != (int)(touchHold * 160)) noticeDirty = true;
  if (touchHold >= 1 && !callAnswerSent) {
    callAnswerSent = true;
    callAnswerAt = now;
    touchHold = 0;
    pendingEvt = "T,A";
    if (wechatAnswer) noticeDirty = true;
  }
}

// 电话听筒：一段拱形的圆点串当手柄，两头各一个大点当听筒和话筒，整体斜过来（常见的电话图标）
void drawHandset(float cx, float cy, float r, uint16_t c) {
  const float ROT = -35 * PI / 180, cr = cosf(ROT), sr = sinf(ROT);
  auto dot = [&](float x, float y, float rr) {
    aaDisc(cx + x * cr - y * sr, cy + x * sr + y * cr, rr, c);
  };
  for (int a = 25; a <= 155; a += 5) {
    float rad = a * PI / 180;
    dot(cosf(rad) * r, -sinf(rad) * r * 0.55f + r * 0.25f, r * 0.22f);
  }
  for (int k = 0; k < 2; k++) {
    float rad = (k ? 155 : 25) * PI / 180;
    dot(cosf(rad) * r + (k ? -1 : 1) * r * 0.04f, -sinf(rad) * r * 0.55f + r * 0.25f + r * 0.21f, r * 0.32f);
  }
}

// Small vector mark: overlapping chat bubbles on WeChat green.
void drawWechatMark(int cx, int cy) {
  const uint16_t green = 0x060C;
  aaDisc(cx, cy, 14, green);
  aaDisc(cx - 4, cy - 3, 7, C_WHITE);
  gfx->fillTriangle(cx - 9, cy + 1, cx - 10, cy + 7, cx - 4, cy + 2, C_WHITE);
  aaDisc(cx + 5, cy + 4, 7, green);
  aaDisc(cx + 5, cy + 4, 6, C_WHITE);
  gfx->fillTriangle(cx + 8, cy + 8, cx + 11, cy + 12, cx + 4, cy + 9, C_WHITE);
  aaDisc(cx - 7, cy - 4, 1, green);
  aaDisc(cx - 2, cy - 4, 1, green);
  aaDisc(cx + 3, cy + 3, 1, green);
  aaDisc(cx + 7, cy + 3, 1, green);
}

void drawCallScreen() {
  onWaitScreen = false;
  frameIsMap = false;
  static unsigned long drawnCallAt = 0;
  static String drawnCallNum;
  static const uint8_t *drawnName = nullptr;
  static uint16_t drawnNameW = 0;
  static uint8_t drawnNameH = 0;
  static bool drawnAnswerSent = false;
  static int drawnHoldWidth = -1;
  const int holdWidth = touchOK && !callAnswerSent ? constrain((int)(touchHold * 160), 0, 160) : 0;
  const bool full = !callIslandDrawn || needFlush || drawnCallAt != callAt ||
      drawnCallNum != callNum || drawnName != imgs[2].show ||
      drawnNameW != imgs[2].showW || drawnNameH != imgs[2].showH ||
      drawnAnswerSent != callAnswerSent;
  if (!full && holdWidth == drawnHoldWidth) return;
  if (full) {
  // One card keeps the title, caller and hold affordance in a single visual
  // group. The previous two overlapping cards made the border look broken and
  // pushed the number into the answer hint on the round screen.
  const int cardX = 84, cardY = 52, cardW = 298, cardH = 142;
  gfx->fillRoundRect(cardX, cardY, cardW, cardH, 30, C_BLACK);
  gfx->drawRoundRect(cardX, cardY, cardW, cardH, 30, C_GREEN);
  aaDisc(118, 80, 14, 0x2DC6);
  drawHandset(118, 82, 8, C_WHITE);
  printCentered("PHONE CALL", CX + 12, 64, 2, C_GREEN);
  bool hasName = imgs[2].show && imgs[2].showW > 0;
  if (hasName) {
    drawTextImg(2, 94, 0xFF);
    if (callNum.length()) printCentered(callNum, CX, 137, 2, C_GREY);
  } else if (callNum.length()) {
    printCentered(callNum, CX, 103, 2, C_WHITE);
  } else {
    printCentered("INCOMING CALL", CX, 103, 2, C_WHITE);
  }
  // Hold progress stays inside the island; a WeChat call never sets callOn.
  if (touchOK) {
    printCentered(callAnswerSent ? "ANSWERING..." : "HOLD SCREEN TO ANSWER", CX, 159, 1,
                  callAnswerSent ? C_GREEN : C_GREY);
  }
  drawnCallAt = callAt;
  drawnCallNum = callNum;
  drawnName = imgs[2].show;
  drawnNameW = imgs[2].showW;
  drawnNameH = imgs[2].showH;
  drawnAnswerSent = callAnswerSent;
  callIslandDrawn = true;
  }
  // This bar is entirely inside the black tab, so a release can erase the
  // previous progress without repainting the rest of the island.
  if (touchOK && (full || holdWidth != drawnHoldWidth)) {
    gfx->fillRect(153, 178, 160, 4, C_BLACK);
    if (holdWidth > 0) gfx->fillRoundRect(153, 178, holdWidth, 4, 2, C_GREEN);
  }
  drawnHoldWidth = holdWidth;
  needFlush = true;
}

void drawNotificationIsland() {
  if (noticeKind == 0 || callOn) return;
  const bool isCall = noticeKind == 2;
  const uint16_t accent = isCall ? C_GREEN : C_CYAN;
  const int cardH = noticeKind == 1 ? 80 : 106;
  gfx->fillRoundRect(94, 49, 292, cardH, 30, C_BLACK);
  gfx->drawRoundRect(94, 49, 292, cardH, 30, accent);
  if (noticeKind == 1) {
    aaDisc(119, 70, 14, C_GREEN);
    drawHandset(119, 72, 8, C_WHITE);
  } else drawWechatMark(119, 70);
  printCentered(noticeKind == 1 ? "PHONE CALL" : isCall ? "WECHAT CALL" : "WECHAT MESSAGE",
                CX + 15, 61, 2, accent);
  int labelSlot = noticeKind == 1 ? 2 : 13;
  if (imgs[labelSlot].show && imgs[labelSlot].showW > 0) drawTextImg(labelSlot, 85, 0xFF);
  else printCentered(noticeKind == 1 ? "INCOMING CALL" : isCall ? "INCOMING CALL" : "NEW MESSAGE",
                     CX, 92, 2, C_WHITE);
  if (isCall) {
    printCentered(callAnswerSent ? "TRYING..." : noticeCanAnswer ? "HOLD TO TRY ANSWER" : "ANSWER ON PHONE",
                  CX, 134, 1, callAnswerSent || noticeCanAnswer ? C_GREEN : C_GREY);
    if (noticeCanAnswer && !callAnswerSent && touchHold > 0) {
      gfx->fillRoundRect(153, 147, constrain((int)(touchHold * 160), 0, 160), 4, 2, C_GREEN);
    }
  }
  needFlush = true;
}
