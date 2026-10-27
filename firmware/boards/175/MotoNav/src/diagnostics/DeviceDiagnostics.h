#pragma once
// 设备身份、能力和启动自检信息。由 MotoNav.ino 按依赖顺序包含，仅编译一次。

String pendingDeviceInfo = "";
String pendingDeviceSelfTest = "";
String pendingOtaCapabilities = "";
String pendingFirmwareVersion = "";
String pendingOtaPower = "";
bool deviceScreenOK = false;
bool deviceTouchOK = false;
bool devicePmuOK = false;
bool deviceBleOK = false;

void queueBleStatus() {
  int queued = 0;
  portENTER_CRITICAL(&mux);
  queued = (rxHead - rxTail + RXQ_SLOTS) % RXQ_SLOTS;
  portEXIT_CRITICAL(&mux);
  const int32_t lastAge = lastBleWriteMs ? (int32_t)(millis() - lastBleWriteMs) : -1;
  pendingBleStatus = String("BLE,") + String(bleConnected ? 1 : 0) + "," +
      String(bleAuthed ? 1 : 0) + "," + String(queued) + "," +
      String(rxDroppedCount) + "," + String(rxOversizeCount) + "," +
      String(bleConnectCount) + "," + String(bleDisconnectCount) + "," +
      String(lastAge);
}

void queueDeviceInfo() {
  // INFO,板型,固件,协议版本,OTA分区,OTA协议,FlashMB,宽,高
  pendingDeviceInfo = String("INFO,") + MOTONAV_BOARD_ID + "," + FW_VERSION + "," +
      String(MOTONAV_PROTOCOL_VERSION) + "," + String(MOTONAV_OTA_PARTITION) + "," +
      String(MOTONAV_OTA_READY) + "," + String(MOTONAV_FLASH_MB) + "," +
      String(LCD_WIDTH) + "," + String(LCD_HEIGHT);
}

void queueDeviceSelfTest() {
  // SELFTEST,screen,touch,pmu,ble,resetReason,wakeup,qspiMHz,freeHeap,freePsram,
  // batteryPct,batteryLevel,charging,usb,batteryMv,pmuTempC10,vbusMv,otaPower
  pendingDeviceSelfTest = String("SELFTEST,") + String(deviceScreenOK ? 1 : 0) + "," +
      String(deviceTouchOK ? 1 : 0) + "," + String(devicePmuOK ? 1 : 0) + "," +
      String(deviceBleOK ? 1 : 0) + "," + String((int)esp_reset_reason()) + "," +
      String((int)esp_sleep_get_wakeup_cause()) + "," + String(qspiMHz) + "," +
      String(ESP.getFreeHeap()) + "," + String(ESP.getFreePsram()) + "," +
      String(batPct) + "," + String(batteryLevel) + "," + String(batChg ? 1 : 0) + "," +
      String(usbIn ? 1 : 0) + "," + String(batMv) + "," + String(pmuTempC10) + "," +
      String(vbusMv) + "," + String(powerAllowsOta() ? 1 : 0);
}

// Keep OTA status packets below the 20-byte BLE fallback MTU used by older
// Android connections. INFO and SELFTEST remain useful diagnostics, but can
// be truncated before MTU negotiation completes.
void queueOtaStatus() {
  pendingOtaCapabilities = String("OC,") + String(OTA_PROTOCOL_VERSION) + "," +
      String(MOTONAV_OTA_READY);
  pendingFirmwareVersion = String("FW,") + FW_VERSION;
  pendingOtaPower = String("OP,") + String(powerAllowsOta() ? 1 : 0);
}

void printDeviceDiagnosticsSerial() {
  queueDeviceInfo();
  queueDeviceSelfTest();
  Serial.println(pendingDeviceInfo);
  Serial.println(pendingDeviceSelfTest);
}
