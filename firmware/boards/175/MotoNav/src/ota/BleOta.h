#pragma once
// BLE OTA transport. The Android app sends the application image only; USB
// remains the recovery path for a complete 0x0 image or a broken install.
#include <Update.h>
#include <esp_ota_ops.h>
#include <mbedtls/base64.h>
#include <mbedtls/pk.h>
#include <mbedtls/sha256.h>
#include "OtaPublicKey.h"

#define OTA_PROTOCOL_VERSION 1
#define OTA_PACKET_MAX 500
#define OTA_PACKET_HEADER 12
#define OTA_PAYLOAD_MAX (OTA_PACKET_MAX - OTA_PACKET_HEADER)

mbedtls_sha256_context otaSha;
bool otaShaReady = false;
uint8_t otaSignature[OTA_SIGNATURE_BYTES];
size_t otaSignatureLen = 0;

uint32_t otaRead32(const uint8_t *p) {
  return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
      ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

String otaField(const String &value, int index) {
  int start = 0;
  for (int i = 0; i < index; i++) {
    const int comma = value.indexOf(',', start);
    if (comma < 0) return "";
    start = comma + 1;
  }
  const int comma = value.indexOf(',', start);
  return comma < 0 ? value.substring(start) : value.substring(start, comma);
}

bool otaHexSha256(const String &value) {
  if (value.length() != 64) return false;
  for (size_t i = 0; i < value.length(); i++) {
    const char c = value.charAt(i);
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') ||
          (c >= 'A' && c <= 'F'))) return false;
  }
  return true;
}

// Compare the numeric major/minor/patch prefix while allowing channel labels
// such as "-power.12" and "-junction.11" to coexist at the same patch level.
void otaVersionPrefix(const String &value, int parts[3]) {
  parts[0] = parts[1] = parts[2] = 0;
  const char *p = value.c_str();
  if (*p == 'v' || *p == 'V') ++p;
  for (int i = 0; i < 3; ++i) {
    if (*p < '0' || *p > '9') return;
    char *end = nullptr;
    parts[i] = (int)strtol(p, &end, 10);
    p = end;
    if (i < 2) {
      if (*p != '.') return;
      ++p;
    }
  }
}

bool otaVersionIsNotOlder(const String &candidate) {
  int next[3], current[3];
  otaVersionPrefix(candidate, next);
  otaVersionPrefix(String(FW_VERSION), current);
  for (int i = 0; i < 3; ++i) {
    if (next[i] > current[i]) return true;
    if (next[i] < current[i]) return false;
  }
  return true;
}

bool otaReadSignature(const String &algorithm, const String &keyId, const String &encoded) {
  if (algorithm != OTA_SIGNING_ALGORITHM || keyId != OTA_SIGNING_KEY_ID || encoded.length() > 360) return false;
  size_t decoded = 0;
  const int status = mbedtls_base64_decode(otaSignature, sizeof(otaSignature), &decoded,
      reinterpret_cast<const unsigned char *>(encoded.c_str()), encoded.length());
  if (status != 0 || decoded != OTA_SIGNATURE_BYTES) {
    memset(otaSignature, 0, sizeof(otaSignature));
    return false;
  }
  otaSignatureLen = decoded;
  return true;
}

bool otaVerifySignature(const uint8_t digest[32]) {
  if (otaSignatureLen != OTA_SIGNATURE_BYTES) return false;
  mbedtls_pk_context key;
  mbedtls_pk_init(&key);
  const int parsed = mbedtls_pk_parse_public_key(&key,
      reinterpret_cast<const unsigned char *>(OTA_PUBLIC_KEY_PEM), sizeof(OTA_PUBLIC_KEY_PEM));
  const int verified = parsed == 0 ? mbedtls_pk_verify(&key, MBEDTLS_MD_SHA256, digest, 0,
      otaSignature, otaSignatureLen) : -1;
  mbedtls_pk_free(&key);
  return verified == 0;
}

void otaNotify(const String &message) {
  if (!otaTxChar || !bleConnected || !bleAuthed) return;
  otaTxChar->setValue((uint8_t *)message.c_str(), message.length());
  otaTxChar->notify();
}

void otaResetState() {
  otaActive = false;
  otaExpectedSize = 0;
  otaWritten = 0;
  otaNextSeq = 0;
  otaVersion = "";
  otaExpectedSha256 = "";
  memset(otaSignature, 0, sizeof(otaSignature));
  otaSignatureLen = 0;
  if (otaShaReady) {
    mbedtls_sha256_free(&otaSha);
    otaShaReady = false;
  }
}

void otaAbort(const char *reason) {
  if (Update.isRunning()) Update.abort();
  if (otaActive) otaNotify(String("OTA,ERR,") + reason);
  otaResetState();
}

void otaHandleBegin(const String &command) {
  if (!bleConnected || !bleAuthed) return;
  if (otaActive) { otaNotify("OTA,ERR,BUSY"); return; }
  if (!powerAllowsOta()) { otaNotify("OTA,ERR,POWER"); return; }
  const String board = otaField(command, 2);
  String version = otaField(command, 3);
  if (!version.startsWith("v")) version = "v" + version;
  const long signedSize = otaField(command, 4).toInt();
  String sha = otaField(command, 5);
  const String algorithm = otaField(command, 6);
  const String keyId = otaField(command, 7);
  const String signature = otaField(command, 8);
  sha.toLowerCase();
  if (board != MOTONAV_BOARD_ID || version.length() == 0 || version.length() > 48 ||
      version == FW_VERSION || signedSize <= 0 || !otaHexSha256(sha)) {
    otaNotify("OTA,ERR,META");
    return;
  }
  const size_t imageSize = (size_t)signedSize;
  if (!otaVersionIsNotOlder(version)) {
    otaNotify("OTA,ERR,DOWNGRADE");
    return;
  }
  if (!otaReadSignature(algorithm, keyId, signature)) {
    otaNotify("OTA,ERR,SIGNATURE");
    return;
  }
  if (!Update.begin(imageSize, U_FLASH)) {
    otaNotify("OTA,ERR,SPACE");
    return;
  }
  mbedtls_sha256_init(&otaSha);
  if (mbedtls_sha256_starts(&otaSha, 0) != 0) {
    Update.abort();
    otaNotify("OTA,ERR,SHA");
    return;
  }
  otaShaReady = true;
  otaActive = true;
  otaExpectedSize = (uint32_t)imageSize;
  otaWritten = 0;
  otaNextSeq = 0;
  otaVersion = version;
  otaExpectedSha256 = sha;
  otaNotify(String("OTA,READY,") + String(OTA_PAYLOAD_MAX) + "," + String(otaExpectedSize));
}

void otaHandleEnd() {
  if (!otaActive) { otaNotify("OTA,ERR,NOSESSION"); return; }
  if (otaWritten != otaExpectedSize || !otaShaReady) {
    otaAbort("SIZE");
    return;
  }
  uint8_t digest[32];
  if (mbedtls_sha256_finish(&otaSha, digest) != 0) {
    otaAbort("SHA");
    return;
  }
  mbedtls_sha256_free(&otaSha);
  otaShaReady = false;
  char actual[65];
  for (int i = 0; i < 32; i++) sprintf(actual + i * 2, "%02x", digest[i]);
  actual[64] = 0;
  if (otaExpectedSha256 != actual || !otaVerifySignature(digest) || !Update.end(true)) {
    if (Update.isRunning()) Update.abort();
    otaNotify("OTA,ERR,VERIFY");
    otaResetState();
    return;
  }
  otaActive = false;
  otaNotify(String("OTA,DONE,") + otaVersion);
  otaVersion = "";
  delay(250);
  ESP.restart();
}

void otaHandleControl(const String &command) {
  if (command == "#OTA,ABORT") { otaAbort("ABORT"); return; }
  if (command == "#OTA,END") { otaHandleEnd(); return; }
  if (command.startsWith("#OTA,BEGIN,")) otaHandleBegin(command);
}

void otaHandlePacket(const uint8_t *data, size_t length) {
  if (!otaActive) { otaNotify("OTA,ERR,NOSESSION"); return; }
  if (length < OTA_PACKET_HEADER || length > OTA_PACKET_MAX || data[0] != 'O' ||
      data[1] != OTA_PROTOCOL_VERSION) {
    otaNotify("OTA,ERR,PACKET");
    return;
  }
  const uint32_t seq = otaRead32(data + 2);
  const uint32_t offset = otaRead32(data + 6);
  const uint16_t count = (uint16_t)data[10] | ((uint16_t)data[11] << 8);
  if (count == 0 || count != length - OTA_PACKET_HEADER || offset + count > otaExpectedSize) {
    otaNotify("OTA,ERR,RANGE");
    return;
  }
  // A retransmitted packet is harmless after a lost ACK; do not write it twice.
  if (seq < otaNextSeq && offset + count <= otaWritten) {
    otaNotify(String("OTA,ACK,") + String(seq) + "," + String(otaWritten));
    return;
  }
  if (seq != otaNextSeq || offset != otaWritten) {
    otaNotify(String("OTA,NACK,") + String(otaNextSeq) + "," + String(otaWritten));
    return;
  }
  uint8_t *payload = const_cast<uint8_t *>(data + OTA_PACKET_HEADER);
  if (Update.write(payload, count) != count ||
      mbedtls_sha256_update(&otaSha, payload, count) != 0) {
    otaAbort("WRITE");
    return;
  }
  otaWritten += count;
  otaNextSeq++;
  otaNotify(String("OTA,ACK,") + String(seq) + "," + String(otaWritten));
}

class OtaRxCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    if (powerOffPending) return;
    const size_t n = c->getLength();
    if (n == 0) return;
    otaHandlePacket(c->getData(), n);
  }
};
