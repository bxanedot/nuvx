








if (DEFINED SIMD64_C_FLAGS OR CMAKE_SYSTEM_PROCESSOR MATCHES "^arm")
  set (TRIAL_C_FLAGS)
else ()
  set (TRIAL_C_FLAGS
    "-mavx"  
    "/arch:AVX"  
  )
  set (TEST_C_SOURCE "
    #ifndef __AVX__
      #error
    #endif
    #include <immintrin.h>
    int main() {return 0;}
    ")
endif ()

include (FindCFlags)

FindCFlags ("SIMD64" "FLOAT-64 (double-precision) SIMD vectorization"
  "${TRIAL_C_FLAGS}" "${TEST_C_SOURCE}")
