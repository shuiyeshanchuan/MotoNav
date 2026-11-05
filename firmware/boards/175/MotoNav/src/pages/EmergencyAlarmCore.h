#pragma once

#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdio.h>

// Phone-owned emergency test UI. This class never detects an accident or sends
// an SMS. Tokens use the phone's persisted uint32 sequence (RFC1982-style order).
namespace EmergencyAlarm {

enum State : uint8_t { NONE = 0, COUNTDOWN = 1, CANCELLED = 2, SENDING = 3,
                       SUBMITTED = 4, FAILED = 5, UNKNOWN = 6,
                       DRILL_COMPLETE = 7, INTERRUPTED = 8 };

inline bool newerToken(uint32_t candidate, uint32_t current) {
  return candidate != current && int32_t(candidate - current) > 0;
}

inline bool parse(const uint8_t *data, size_t length, uint32_t &token,
                  uint8_t &state, uint16_t &remaining) {
  // '#EA,' + exactly eight hexadecimal digits + ',' + state + ',' + seconds.
  if (!data || length < 16 || length > 19 || memcmp(data, "#EA,", 4)) return false;
  token = 0;
  for (size_t i = 4; i < 12; ++i) {
    const uint8_t ch = data[i];
    uint8_t digit;
    if (ch >= '0' && ch <= '9') digit = ch - '0';
    else if (ch >= 'a' && ch <= 'f') digit = ch - 'a' + 10;
    else if (ch >= 'A' && ch <= 'F') digit = ch - 'A' + 10;
    else return false;
    token = (token << 4) | digit;
  }
  if (!token || data[12] != ',' || data[13] < '1' || data[13] > '8' || data[14] != ',') return false;
  state = data[13] - '0';
  uint32_t seconds = 0;
  for (size_t i = 15; i < length; ++i) {
    if (data[i] < '0' || data[i] > '9') return false;
    seconds = seconds * 10 + data[i] - '0';
    if (seconds > 3600) return false;
  }
  remaining = uint16_t(seconds);
  return true;
}

class Model {
public:
  bool receive(uint32_t token, uint8_t state, uint16_t seconds, uint32_t now) {
    if (!token || state < COUNTDOWN || state > INTERRUPTED || seconds > 3600) return false;
    if (token != token_) {
      // A delayed result can never open a new event. Only a newer countdown
      // starts one, including when the previous overlay has already closed.
      if (state != COUNTDOWN || (token_ && !newerToken(token, token_))) return false;
      token_ = token; state_ = COUNTDOWN; visible_ = true;
      deadline_ = now + uint32_t(seconds) * 1000;
      cancelPending_ = false; haveCancelNotify_ = false;
      resetTouch(true);
      return true;
    }
    if (state == COUNTDOWN) {
      if (state_ != COUNTDOWN) return false;
      const uint32_t candidate = now + uint32_t(seconds) * 1000;
      // Heartbeats may shorten a deadline; delayed or rounded-up updates must
      // never prolong it or restart an expired countdown.
      if (int32_t(candidate - deadline_) < 0) deadline_ = candidate;
      return true;
    }
    if (state == state_) {
      if (state >= SENDING) cancelPending_ = false;
      return true;  // Duplicate results do not extend visibility.
    }
    if (state_ == CANCELLED || state_ == SUBMITTED || state_ == FAILED ||
        state_ == DRILL_COMPLETE || state_ == INTERRUPTED) return false;
    if (state_ == SENDING && state != SUBMITTED && state != FAILED &&
        state != UNKNOWN && state != INTERRUPTED)
      return false;
    if (state_ == UNKNOWN && state != SUBMITTED && state != FAILED && state != INTERRUPTED)
      return false;
    state_ = static_cast<State>(state);
    // Once the phone has entered processing/result state, the local request is
    // no longer actionable. State 2 is accepted only while still counting down.
    if (state >= SENDING) cancelPending_ = false;
    visible_ = true;
    terminalAt_ = now;
    resetTouch(true);
    if (state == CANCELLED) cancelPending_ = false;
    return true;
  }

  void tick(uint32_t now) {
    if (!visible_ || state_ == COUNTDOWN || cancelPending_) return;
    const uint32_t duration = state_ == CANCELLED ? 2000 : 10000;
    if (uint32_t(now - terminalAt_) >= duration) {
      visible_ = false;
      resetTouch(true);
    }
  }

  void touch(bool down, int16_t x, int16_t y, uint32_t now) {
    if (!visible_ || state_ != COUNTDOWN || cancelPending_) { resetTouch(true); return; }
    if (needsRelease_) {
      if (!down) needsRelease_ = false;
      return;
    }
    if (!down) { holding_ = false; return; }
    if (!holding_) {
      holding_ = true; holdAt_ = now; holdX_ = x; holdY_ = y;
    }
    const int dx = int(x) - holdX_, dy = int(y) - holdY_;
    if (dx < -36 || dx > 36 || dy < -36 || dy > 36) { resetTouch(true); return; }
    if (uint32_t(now - holdAt_) >= 500) {
      cancelPending_ = true;
      haveCancelNotify_ = false;
      resetTouch(true);
    }
  }

  void suspendTouch() { resetTouch(true); }
  void disconnected() {
    // Keep both token and pending request. Reconnect sends the request before
    // waiting for another countdown heartbeat from the phone.
    haveCancelNotify_ = false;
    resetTouch(true);
  }
  size_t cancelNotification(uint32_t now, char *out, size_t capacity) {
    if (!cancelPending_ || !out || capacity < 14) return 0;
    if (haveCancelNotify_ && uint32_t(now - lastCancelNotifyAt_) < 1000) return 0;
    const int length = snprintf(out, capacity, "EA,C,%08lX", static_cast<unsigned long>(token_));
    if (length != 13 || size_t(length) >= capacity) return 0;
    haveCancelNotify_ = true; lastCancelNotifyAt_ = now;
    return size_t(length);
  }

  uint16_t remaining(uint32_t now) const {
    if (state_ != COUNTDOWN || int32_t(deadline_ - now) <= 0) return 0;
    return uint16_t((uint32_t(deadline_ - now) + 999) / 1000);
  }
  uint16_t holdPermille(uint32_t now) const {
    if (!holding_) return 0;
    const uint32_t elapsed = now - holdAt_;
    return elapsed >= 500 ? 1000 : uint16_t(elapsed * 2);
  }
  bool visible() const { return visible_; }
  bool cancelPending() const { return cancelPending_; }
  uint32_t token() const { return token_; }
  State state() const { return state_; }

private:
  void resetTouch(bool requireRelease) {
    holding_ = false;
    needsRelease_ = requireRelease;
  }
  uint32_t token_ = 0, deadline_ = 0, terminalAt_ = 0;
  State state_ = NONE;
  bool visible_ = false, cancelPending_ = false;
  bool needsRelease_ = true, holding_ = false, haveCancelNotify_ = false;
  uint32_t holdAt_ = 0, lastCancelNotifyAt_ = 0;
  int16_t holdX_ = 0, holdY_ = 0;
};

}  // namespace EmergencyAlarm
