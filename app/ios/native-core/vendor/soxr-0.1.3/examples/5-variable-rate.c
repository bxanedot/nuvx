










#include <soxr.h>
#include "examples-common.h"

#define OCTAVES  5        
#define OLEN     16       
#define FS       44100    


static double ioratio(double pos, int fm)
{
  if (fm)  
    pos = .5 - cos(pos * 2 * M_PI) * .4 + sin(pos * OLEN * 20 * M_PI) * .05;
  return pow(2, 2 * OCTAVES * pos - OCTAVES);
}

int main(int argc, char *arg[])
{
  int opt = argc <= 1? 2 : (atoi(arg[1]) & 3), saw = opt & 1, fm = opt & 2;
  float ibuf[10 << OCTAVES], obuf[AL(ibuf)];
  int i, wl = 2 << OCTAVES;
  size_t ilen = AL(ibuf), need_input = 1, written;
  size_t odone, total_odone, total_olen = OLEN * FS;
  size_t olen1 = fm? 10 : AL(obuf);  
  soxr_error_t error;


  soxr_quality_spec_t q_spec = soxr_quality_spec(SOXR_HQ, SOXR_VR);



  soxr_t soxr = soxr_create(1 << OCTAVES, 1, 1, &error, NULL, &q_spec, NULL);

  if (!error) {
    USE_STD_STDIO;


    for (i = 0; i < (int)ilen; ++i)
      ibuf[i] = (float)(saw? (i%wl)/(wl-1.)-.5 : .9 * sin(2 * M_PI * i / wl));


    soxr_set_io_ratio(soxr, ioratio(0, fm), 0);


    for (total_odone = 0; !error && total_odone < total_olen;) {


      size_t block_len = min(olen1, total_olen - total_odone);


      double pos = (double)(total_odone + block_len) / (double)total_olen;




      soxr_set_io_ratio(soxr, ioratio(pos, fm), block_len);


      do {
        size_t len = need_input? ilen : 0;
        error = soxr_process(soxr, ibuf, len, NULL, obuf, block_len, &odone);
        written = fwrite(obuf, sizeof(float), odone, stdout);


        block_len -= odone;
        total_odone += odone;



        need_input = block_len != 0;

      } while (need_input && !error && written == odone);



    }
    soxr_delete(soxr);
  }

  fprintf(stderr, "%-26s %s; I/O: %s\n", arg[0], soxr_strerror(error),
      ferror(stdin) || ferror(stdout)? strerror(errno) : "no error");
  return !!error;
}
