#pragma once

#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

// Wire's internal lock does not cover requestFrom() followed by readBytes().
// All clients must hold this lock through the entire register transaction.
static SemaphoreHandle_t sharedWireMutex = nullptr;

inline void initSharedWire() { sharedWireMutex = xSemaphoreCreateMutex(); }

class SharedWireGuard {
public:
  SharedWireGuard() {
    if (sharedWireMutex) xSemaphoreTake(sharedWireMutex, portMAX_DELAY);
  }
  ~SharedWireGuard() { if (sharedWireMutex) xSemaphoreGive(sharedWireMutex); }
  SharedWireGuard(const SharedWireGuard &) = delete;
  SharedWireGuard &operator=(const SharedWireGuard &) = delete;
};
