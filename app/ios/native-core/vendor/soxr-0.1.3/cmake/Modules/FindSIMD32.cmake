# SoX Resampler Library       Copyright (c) 2007-16 robs@users.sourceforge.net
# Licence for this file: LGPL v2.1                  See LICENCE for details.







if (DEFINED SIMD32_C_FLAGS)
  set (TRIAL_C_FLAGS)
elseif (CMAKE_SYSTEM_PROCESSOR MATCHES "^arm")
  set (TRIAL_C_FLAGS

    "-mfpu=neon-vfpv4 -mcpu=cortex-a7"
    "-mfpu=neon       -mfloat-abi=hard"
    "-mfpu=neon       -mfloat-abi=softfp"
    "-mfpu=neon       -mfloat-abi=soft"
  )
  set (TEST_C_SOURCE "
    #include <arm_neon.h>
    int main(int c, char * * v) {
      float32x4_t a = vdupq_n_f32((float)c), b = vdupq_n_f32((float)!!v);
      return !vgetq_lane_u32(vceqq_f32(a,b),0);
    }
  ")
else ()
  if (WIN32)  
    set (GCC_WIN32_SIMD32_OPTS "-mincoming-stack-boundary=2")
  endif ()

  set (TRIAL_C_FLAGS

    " "

    "/arch:SSE /fp:fast -D__SSE__"

    "-msse -mfpmath=sse ${GCC_WIN32_SIMD32_OPTS}"

    "-msse -mfpmath=sse"
  )
  set (TEST_C_SOURCE "
    #include <xmmintrin.h>
    int main(int c, char * * v) {
      __m128 a = _mm_set_ss((float)c), b = _mm_set_ss((float)!!v);
      return _mm_comineq_ss(a,b);
    }
  ")
endif ()

include (FindCFlags)

FindCFlags ("SIMD32" "FLOAT-32 (single-precision) SIMD vectorization"
  "${TRIAL_C_FLAGS}" "${TEST_C_SOURCE}")
