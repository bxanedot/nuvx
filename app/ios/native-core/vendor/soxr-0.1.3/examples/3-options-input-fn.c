
























#include <soxr.h>
#include "examples-common.h"

typedef struct {void * ibuf; size_t isize;} input_context_t;

static size_t input_fn(input_context_t * p, soxr_cbuf_t * buf, size_t len)
{

  len = fread(p->ibuf, p->isize, len, stdin);  



  *buf = (!len && ferror(stdin))? NULL : p->ibuf;   

  return len;                            
}

int main(int n, char const * arg[])
{
  char const *     const arg0 = n? --n, *arg++ : "", * engine = "";
  double          const irate = n? --n, atof(*arg++) : 96000.;
  double          const orate = n? --n, atof(*arg++) : 44100.;
  unsigned        const chans = n? --n, (unsigned)atoi(*arg++) : 1;
  soxr_datatype_t const itype = n? --n, (soxr_datatype_t)atoi(*arg++) : 0;
  unsigned        const ospec = n? --n, (soxr_datatype_t)atoi(*arg++) : 0;
  unsigned long const q_recipe= n? --n, strtoul(*arg++, 0, 16) : SOXR_HQ;
  unsigned long const q_flags = n? --n, strtoul(*arg++, 0, 16) : 0;
  double   const passband_end = n? --n, atof(*arg++) : 0;
  double const stopband_begin = n? --n, atof(*arg++) : 0;
  double const phase_response = n? --n, atof(*arg++) : -1;
  int       const use_threads = n? --n, atoi(*arg++) : 1;
  soxr_datatype_t const otype = ospec & 3;

  soxr_quality_spec_t       q_spec = soxr_quality_spec(q_recipe, q_flags);
  soxr_io_spec_t            io_spec = soxr_io_spec(itype, otype);
  soxr_runtime_spec_t const runtime_spec = soxr_runtime_spec(!use_threads);



  #define buf_total_len 15000   
  size_t const osize = soxr_datatype_size(otype) * chans;
  size_t const isize = soxr_datatype_size(itype) * chans;
  size_t const olen0= (size_t)(orate * buf_total_len / (irate + orate) + .5);
  size_t const olen = min(max(olen0, 1), buf_total_len - 1);
  size_t const ilen = buf_total_len - olen;
  void * const obuf = malloc(osize * olen);
  void * const ibuf = malloc(isize * ilen);

  input_context_t icontext;
  size_t odone, clips = 0;
  soxr_error_t error;
  soxr_t soxr;


  if (passband_end   > 0) q_spec.passband_end   = passband_end / 100;
  if (stopband_begin > 0) q_spec.stopband_begin = stopband_begin / 100;
  if (phase_response >=0) q_spec.phase_response = phase_response;
  io_spec.flags = ospec & ~7u;


  soxr = soxr_create(
      irate, orate, chans,          
      &error,                          
      &io_spec, &q_spec, &runtime_spec);

  if (!error) {                       
    icontext.ibuf = ibuf, icontext.isize = isize;
    error = soxr_set_input_fn(soxr, (soxr_input_fn_t)input_fn, &icontext, ilen);
  }

  if (!error) {                          
    engine = soxr_engine(soxr);
    USE_STD_STDIO;

    do odone = soxr_output(soxr, obuf, olen);
    while (fwrite(obuf, osize, odone, stdout));             

    error = soxr_error(soxr);             
    clips = *soxr_num_clips(soxr);      
  }

  soxr_delete(soxr);
  free(obuf), free(ibuf);

  fprintf(stderr, "%-26s %s; %lu clips; I/O: %s (%s)\n",
      arg0, soxr_strerror(error), (long unsigned)clips,
      ferror(stdin) || ferror(stdout)? strerror(errno) : "no error", engine);
  return !!error;
}
