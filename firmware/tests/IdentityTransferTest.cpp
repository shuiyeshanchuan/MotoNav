#include <cassert>
#include <cstring>
#include <fstream>
#include <vector>
#include "../boards/175/MotoNav/src/connectivity/IdentityTransfer.h"

int main(int argc, char **argv) {
  IdentityTransfer transfer;
  const char *info = "INFO,175C,v7.12.9-nav.15-imu-sampling-log-test,1,1,1,32,466,466";
  assert(IdentityTransfer::checksum(reinterpret_cast<const uint8_t *>("123456789"), 9) == 0xcbf43926u);
  assert(!transfer.begin(nullptr));
  uint8_t packet[168], retry[168];
  assert(!transfer.next(23, packet, sizeof(packet)));
  assert(transfer.begin(info));
  assert(!transfer.next(22, packet, sizeof(packet)));
  std::vector<uint8_t> reconstructed;
  std::ofstream golden;
  if (argc == 2) golden.open(argv[1], std::ios::binary);
  while (const size_t n = transfer.next(23, packet, sizeof(packet))) {
    assert(n <= 20 && packet[0] == 0xd7 && packet[1] == 0x49 && packet[2] == 1);
    assert(transfer.next(23, retry, sizeof(retry)) == n);
    assert(memcmp(packet, retry, n) == 0); // no advancement before accepted()
    const size_t offset = packet[4] | (size_t(packet[5]) << 8);
    assert(offset == reconstructed.size());
    reconstructed.insert(reconstructed.end(), packet + 8, packet + n);
    if (golden) { golden.put(char(n)); golden.write(reinterpret_cast<const char *>(packet), n); }
    transfer.accepted();
  }
  assert(reconstructed.size() == strlen(info) + 4);
  assert(memcmp(reconstructed.data(), info, strlen(info)) == 0);
  const size_t n = reconstructed.size() - 4;
  uint32_t crc = 0;
  for (unsigned i = 0; i < 4; ++i) crc |= uint32_t(reconstructed[n+i]) << (8*i);
  assert(crc == IdentityTransfer::checksum(reconstructed.data(), n));
  assert(transfer.begin(info));
  assert(transfer.next(517, packet, sizeof(packet)) == strlen(info) + 12);
  transfer.reset();
  assert(!transfer.next(23, packet, sizeof(packet)));
  char large[158]; memset(large, 'A', 157); large[157] = 0;
  assert(!transfer.begin(large));
  puts("IdentityTransfer native: CRC, fallback MTU, bounded body, retry/reset and golden frame passed");
}
