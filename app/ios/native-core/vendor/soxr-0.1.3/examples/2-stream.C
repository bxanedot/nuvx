/* SoX Resampler Library      Copyright (c) 2007-13 robs@users.sourceforge.net
 * Licence for this file: LGPL v2.1                  See LICENCE for details. */












#include <soxr.h>
#include "examples-common.h"

int main(int argc, char const * arg[])
{
  double const irate = argc > 1? atof(arg[1]) : 96000.;
  double const orate = argc > 2? atof(arg[2]) : 44100.;



  #define buf_total_len 15000   
  size_t const olen = (size_t)(orate * buf_total_len / (irate + orate) + .5);
  size_t const ilen = buf_total_len - olen;
  size_t const osize = sizeof(float), isize = osize;
  void * obuf = malloc(osize * olen);
  void * ibuf = malloc(isize * ilen);

  size_t odone, written, need_input = 1;
  soxr_error_t error;


  soxr_t soxr = soxr_create(
      irate, orate, 1,              
      &error,                          
      NULL, NULL, NULL);                         

  if (!error) {                          
    USE_STD_STDIO;

    do {
      size_t ilen1 = 0;

      if (need_input) {


        ilen1 = fread(ibuf, isize, ilen, stdin);

        if (!ilen1) {      
          free(ibuf);      
          ibuf = NULL;     
        }
      }



      error = soxr_process(soxr, ibuf, ilen1, NULL, obuf, olen, &odone);

      written = fwrite(obuf, osize, odone, stdout);  




      need_input = odone < olen && ibuf;

    } while (!error && (need_input || written));
  }

  soxr_delete(soxr);
  free(obuf), free(ibuf);

  fprintf(stderr, "%-26s %s; I/O: %s\n", arg[0], soxr_strerror(error),
      ferror(stdin) || ferror(stdout)? strerror(errno) : "no error");
  return !!error;
}
