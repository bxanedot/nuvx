


#if !defined PFFT_MACROS_ONLY

/* Copyright (c) 2013  Julien Pommier ( pommier@modartt.com )

   Based on original fortran 77 code from FFTPACKv4 from NETLIB,
   authored by Dr Paul Swarztrauber of NCAR, in 1985.

   As confirmed by the NCAR fftpack software curators, the following
   FFTPACKv5 license applies to FFTPACKv4 sources. My changes are
   released under the same terms.

   FFTPACK license:

   http://www.cisl.ucar.edu/css/software/fftpack5/ftpk.html

   Copyright (c) 2004 the University Corporation for Atmospheric
   Research ("UCAR"). All rights reserved. Developed by NCAR's
   Computational and Information Systems Laboratory, UCAR,
   www.cisl.ucar.edu.

   Redistribution and use of the Software in source and binary forms,
   with or without modification, is permitted provided that the
   following conditions are met:

   - Neither the names of NCAR's Computational and Information Systems
   Laboratory, the University Corporation for Atmospheric Research,
   nor the names of its sponsors or contributors may be used to
   endorse or promote products derived from this Software without
   specific prior written permission.

   - Redistributions of source code must retain the above copyright
   notices, this list of conditions, and the disclaimer below.

   - Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions, and the disclaimer below in the
   documentation and/or other materials provided with the
   distribution.

   THIS SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
   EXPRESS OR IMPLIED, INCLUDING, BUT NOT LIMITED TO THE WARRANTIES OF
   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
   NONINFRINGEMENT. IN NO EVENT SHALL THE CONTRIBUTORS OR COPYRIGHT
   HOLDERS BE LIABLE FOR ANY CLAIM, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES OR OTHER LIABILITY, WHETHER IN AN
   ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
   CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS WITH THE
   SOFTWARE.
*/






























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
