#include <cassert>
#include <cstdio>
#include <cstring>
#include "../boards/175/MotoNav/src/pages/EmergencyAlarmCore.h"

using namespace EmergencyAlarm;

static void testParser() {
  uint32_t token; uint8_t state; uint16_t seconds;
  const char *valid = "#EA,aBcD1200,1,30";
  assert(parse(reinterpret_cast<const uint8_t *>(valid), strlen(valid), token, state, seconds));
  assert(token == 0xABCD1200 && state == COUNTDOWN && seconds == 30);
  const char *invalid[] = {"#EA,00000000,1,30", "#EA,ABCD120,1,30", "#EA,ABCD12000,1,30",
      "#EA,ABCD12x0,1,30", "#EA,ABCD1200,9,30", "#EA,ABCD1200,1,-1",
      "#EA,ABCD1200,1,3601", "#EA,ABCD1200,1,30,", "#EA,ABCD1200,1,", "#EA,ABCD1200,1,30\n"};
  for (const char *frame : invalid)
    assert(!parse(reinterpret_cast<const uint8_t *>(frame), strlen(frame), token, state, seconds));
  assert(!parse(nullptr, 18, token, state, seconds));
}

static void testDeadlineAndStaleFrames() {
  Model alarm;
  assert(!alarm.receive(9, SENDING, 0, 0));
  assert(alarm.receive(10, COUNTDOWN, 30, 100));
  assert(alarm.visible() && alarm.remaining(100) == 30);
  assert(alarm.remaining(1100) == 29);
  assert(alarm.receive(10, COUNTDOWN, 30, 2100));
  assert(alarm.remaining(2100) == 28);  // No extension by a delayed heartbeat.
  assert(alarm.receive(10, COUNTDOWN, 10, 3100));
  assert(alarm.remaining(3100) == 10);
  assert(!alarm.receive(9, COUNTDOWN, 30, 3200));
  assert(!alarm.receive(11, CANCELLED, 0, 3200));
  assert(alarm.remaining(13100) == 0 && alarm.state() == COUNTDOWN);
  assert(alarm.receive(10, COUNTDOWN, 30, 14000));
  assert(alarm.remaining(14000) == 0);  // Zero waits for the phone, never claims SMS.
  assert(alarm.receive(10, SUBMITTED, 0, 15000));
  assert(!alarm.receive(10, COUNTDOWN, 30, 15100));
  assert(!alarm.receive(10, CANCELLED, 0, 15100));
  assert(alarm.state() == SUBMITTED);
  alarm.tick(24999); assert(alarm.visible());
  alarm.tick(25000); assert(!alarm.visible());
  assert(!alarm.receive(9, COUNTDOWN, 30, 25001));
  assert(!alarm.receive(10, COUNTDOWN, 30, 25001));
  assert(alarm.receive(11, COUNTDOWN, 30, 25002));
  assert(alarm.token() == 11 && alarm.visible());
}

static void cancelByHold(Model &alarm, uint32_t now) {
  alarm.touch(false, 0, 0, now);
  alarm.touch(true, 230, 350, now + 1);
  alarm.touch(true, 235, 352, now + 500);
  assert(!alarm.cancelPending());
  assert(alarm.holdPermille(now + 500) == 998);
  alarm.touch(true, 235, 352, now + 501);
  assert(alarm.cancelPending());
  assert(alarm.state() == COUNTDOWN);  // Local request alone is not confirmation.
}

static void testHoldAndConfirmation() {
  Model alarm;
  alarm.receive(10, COUNTDOWN, 30, 0);
  // A finger already held on the previous page cannot cancel this event.
  alarm.touch(true, 200, 200, 0);
  alarm.touch(true, 200, 200, 600);
  assert(!alarm.cancelPending());
  cancelByHold(alarm, 1000);
  char request[20] = {};
  assert(alarm.cancelNotification(1501, request, sizeof(request)) == 13);
  assert(!strcmp(request, "EA,C,0000000A"));
  assert(!alarm.cancelNotification(2500, request, sizeof(request)));
  assert(alarm.cancelNotification(2501, request, sizeof(request)) == 13);
  assert(!alarm.receive(9, CANCELLED, 0, 2600));
  assert(alarm.cancelPending() && alarm.state() == COUNTDOWN);
  alarm.receive(10, COUNTDOWN, 30, 2700);
  assert(alarm.cancelPending());
  assert(alarm.receive(10, CANCELLED, 0, 2800));
  assert(!alarm.cancelPending() && alarm.state() == CANCELLED);
  assert(!alarm.cancelNotification(3000, request, sizeof(request)));
  alarm.receive(10, CANCELLED, 0, 4700);  // Duplicate cannot keep the page open.
  alarm.tick(4799); assert(alarm.visible());
  alarm.tick(4800); assert(!alarm.visible());
}

static void testInterruptedHoldAndHigherPriorityModal() {
  Model alarm;
  alarm.receive(1, COUNTDOWN, 30, 0);
  alarm.touch(false, 0, 0, 0);
  alarm.touch(true, 200, 200, 100);
  alarm.touch(true, 250, 200, 400);  // A swipe is not a hold.
  alarm.touch(true, 250, 200, 1000);
  assert(!alarm.cancelPending());
  alarm.touch(false, 0, 0, 1100);
  alarm.touch(true, 200, 200, 1200);
  alarm.touch(false, 0, 0, 1400);  // Missing/released contact restarts timing.
  alarm.touch(true, 200, 200, 1500);
  assert(alarm.holdPermille(1500) == 0);
  alarm.suspendTouch();
  alarm.touch(true, 200, 200, 2200);
  assert(!alarm.cancelPending());
  cancelByHold(alarm, 2300);
}

static void testDisconnectedPendingRequest() {
  Model alarm;
  alarm.receive(100, COUNTDOWN, 3, 0);
  cancelByHold(alarm, 0);
  char request[20] = {};
  assert(alarm.cancelNotification(501, request, sizeof(request)));
  alarm.disconnected();
  alarm.tick(50000);
  assert(alarm.visible() && alarm.cancelPending() && alarm.remaining(50000) == 0);
  assert(alarm.cancelNotification(50002, request, sizeof(request))); // Immediate reconnect retry.
  alarm.receive(100, SENDING, 0, 50003);
  alarm.touch(false, 0, 0, 50004);
  alarm.touch(true, 200, 200, 50100);
  alarm.touch(true, 200, 200, 50700);
  assert(alarm.state() == SENDING); // No cancellation claim after dispatch starts.
  assert(!alarm.cancelPending());
  alarm.tick(60002); assert(alarm.visible());
  assert(!alarm.receive(100, CANCELLED, 0, 60002));
  assert(!alarm.cancelPending());
  assert(!alarm.cancelNotification(60002, request, sizeof(request)));
  alarm.tick(60003); assert(!alarm.visible());
}

static void testTokenAndClockWrap() {
  Model alarm;
  assert(alarm.receive(0xFFFFFFFE, COUNTDOWN, 2, 0xFFFFFF00));
  assert(alarm.remaining(0xFFFFFF00) == 2);
  assert(alarm.remaining(0x000002E8) == 1);
  assert(alarm.remaining(0x000006D0) == 0);
  assert(alarm.receive(1, COUNTDOWN, 30, 0x700));
  assert(!alarm.receive(0xFFFFFFFF, COUNTDOWN, 30, 0x800));
  assert(!alarm.receive(0xFFFFFFFE, CANCELLED, 0, 0x800));
  assert(alarm.token() == 1 && alarm.state() == COUNTDOWN);

  Model hold;
  hold.receive(1, COUNTDOWN, 30, 0xFFFFFE00);
  cancelByHold(hold, 0xFFFFFF00);
  char request[20] = {};
  assert(hold.cancelNotification(0xF5, request, sizeof(request)));
  assert(!hold.cancelNotification(0x4DC, request, sizeof(request)));
  assert(hold.cancelNotification(0x4DD, request, sizeof(request)));
}

static void testRealPhoneResults() {
  for (uint8_t result = SENDING; result <= INTERRUPTED; ++result) {
    Model alarm;
    alarm.receive(1, COUNTDOWN, 30, 0);
    assert(alarm.receive(1, result, 0, 100));
    alarm.touch(false, 0, 0, 100);
    alarm.touch(true, 200, 200, 200);
    alarm.touch(true, 200, 200, 1000);
    assert(!alarm.cancelPending());
    alarm.tick(10099); assert(alarm.visible());
    alarm.tick(10100); assert(!alarm.visible());
    assert(!alarm.receive(1, COUNTDOWN, 30, 11000));
  }
  Model alarm;
  alarm.receive(1, COUNTDOWN, 30, 0);
  alarm.receive(1, SENDING, 0, 100);
  assert(!alarm.receive(1, CANCELLED, 0, 150));
  assert(!alarm.cancelPending());
  alarm.receive(1, UNKNOWN, 0, 200);
  assert(alarm.receive(1, SUBMITTED, 0, 300));
  assert(alarm.state() == SUBMITTED);
}

int main() {
  testParser(); testDeadlineAndStaleFrames(); testHoldAndConfirmation();
  testInterruptedHoldAndHigherPriorityModal(); testDisconnectedPendingRequest();
  testTokenAndClockWrap(); testRealPhoneResults();
  std::puts("PASS: emergency overlay parsing, tokens, local countdown, 500ms hold, phone ACK, retry, result states and clock wrap");
}
