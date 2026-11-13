


#if !defined PFFT_MACROS_ONLY













































































#ifndef PFFFT_H
#define PFFFT_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#if PFFFT_DOUBLE
#define float double
#endif





  typedef struct PFFFT_Setup PFFFT_Setup;


  typedef enum { PFFFT_FORWARD, PFFFT_BACKWARD } pffft_direction_t;


  typedef enum { PFFFT_REAL, PFFFT_COMPLEX } pffft_transform_t;






  static
  PFFFT_Setup *pffft_new_setup(int N, pffft_transform_t transform);
  static
  void pffft_destroy_setup(PFFFT_Setup *);


















  static
  void pffft_transform(PFFFT_Setup *setup, const float *input, float *output, float *work, pffft_direction_t direction);








  static
  void pffft_transform_ordered(PFFFT_Setup *setup, const float *input, float *output, float *work, pffft_direction_t direction);













  static
  void pffft_zreorder(PFFFT_Setup *setup, const float *input, float *output, pffft_direction_t direction);













  void pffft_zconvolve_accumulate(PFFFT_Setup *setup, const float *dft_a, const float *dft_b, float *dft_ab, float scaling);






#if 0
  void *pffft_aligned_malloc(size_t nb_bytes);
  void pffft_aligned_free(void *);


  int pffft_simd_size();
#endif

#undef float

#ifdef __cplusplus
}
#endif

#endif

#endif
