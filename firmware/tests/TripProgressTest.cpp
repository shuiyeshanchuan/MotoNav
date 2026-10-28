#include <cassert>
#include <cmath>
#include <cstdio>
#include "../boards/175/MotoNav/TripProgress.h"

static bool close(float a, float b) { return std::fabs(a-b) < 0.00001f; }

int main() {
  TripProgress p;
  p.sample(0.2f, 100);
  p.sample(0.6f, 1100);
  assert(close(p.value(1100), 0.2f));
  assert(close(p.value(1350), 0.2625f));
  assert(close(p.value(1600), 0.4f));
  assert(close(p.value(1850), 0.5375f));
  assert(close(p.value(2100), 0.6f));
  assert(!p.moving(2100));
  p.reset();
  assert(close(p.value(2200), 0));
  puts("PASS: eased trip progress, endpoints, reset");
}
