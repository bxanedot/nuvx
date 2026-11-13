/* SoX Resampler Library      Copyright (c) 2007-16 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */





#define TYPE 0  

#if TYPE > 1
  #include <quadmath.h>
#endif

#include "math-wrap.h"
#include <stdlib.h>
#include <stdio.h>

#if TYPE
  #if TYPE > 1
    #define modf modfq
    #define cos cosq
    #define sin sinq
    #define PI M_PIq
    #define real __float128
    #define atof(x) strtoflt128(x, 0)
  #else
    #define modf modfl
    #define cos cosl
    #define sin sinl
    #define PI M_PIl
    #define real long double
  #endif
  #define MULT 1
  #define OUT(d) double output = d
#else
  #define PI M_PI
  #define real double
  #include "rint.h"
  #define MULT (32768. * 65536 - 1/scale)
  #define OUT(d) int32_t output = rint32(d)
#endif

int main(int argc, char const * argv[])
{
  real rate         = atof(argv[1]),  
       lead_in_len  = atof(argv[2]),  
       len          = atof(argv[3]),  
       f1           = atof(argv[4]),
       f2           = atof(argv[5]),
       scale        = atof(argv[6]),  
       n1 = rate * -lead_in_len,
       m = (f2 - f1) / (rate * len * 2), dummy;
  FILE * file = fopen(argv[7], "wb");
  int i = (int)n1, err = !file || i != n1;
  for (; !err && i < (int)(rate*(len+lead_in_len)+.5); ++i) {
    real d = sin(2 * PI * modf((f1 + i * m) * i / rate, &dummy));
    OUT((double)(scale * MULT * d));
    err = fwrite(&output, sizeof(output), 1, file) != 1;
  }
  return err |!argc;
}
