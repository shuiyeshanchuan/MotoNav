#pragma once
// OTA signing public key. Its private counterpart is deliberately stored outside
// the repository at the user-selected location and must never be committed.
#define OTA_SIGNING_ALGORITHM "RSA-SHA256-PKCS1v1.5"
#define OTA_SIGNING_KEY_ID "4b5022f6c387bbd0"
#define OTA_SIGNATURE_BYTES 256

static const char OTA_PUBLIC_KEY_PEM[] PROGMEM = R"KEY(-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAz3MEMZvaayx5AVkeMBGY
T148gUlzM6LqUMoG4OwnUJFCXdzbWoL9IWwrwqDM+1iqnfrZFj+WqFOoJtz/5Uv6
53kSLUgz4YSPay0BBGCuB3Bb20x9mJG40TIG8I3EQbJXOXTomX+zz16laema4CX5
kxAEYqKmDXie8LG0TAZpttMXQdaimy1tu+lXGF0qLd/gkZ35zPEIVaLYl3ES3SMm
PLC1aD6oPoU4YpKGAHYSSU2hGutLJ9p2EkbxKSTOflj8GhccSlIeyx+kIn1hc4P6
J/Law2UNXqxdNKjAp8a+LXAPiA5hEsxRnOBlHqYhoDYn1JhBFuVwm+hgFIPwGIfo
9QIDAQAB
-----END PUBLIC KEY-----
)KEY";
