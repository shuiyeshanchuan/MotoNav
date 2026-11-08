#pragma once
#include <stdlib.h>
static constexpr int MALLOC_CAP_SPIRAM = 1, MALLOC_CAP_8BIT = 2;
inline void *heap_caps_malloc(size_t bytes, int) { return malloc(bytes); }
