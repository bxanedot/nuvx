

















#include <soxr.h>
#include "examples-common.h"

const float in[] = {   
  0,1,0,-1, 0,1,0,-1, 0,1,0,-1, 0,1,0,-1, 0,1,0,-1, 0,1,0,-1,
  0,1,0,-1, 0,1,0,-1, 0,1,0,-1, 0,1,0,-1, 0,1,0,-1, 0,1,0,-1};

int main(int argc, char const * arg[])
{
  double irate = argc > 1? atof(arg[1]) : 1;       
  double orate = argc > 2? atof(arg[2]) : 2;              

  size_t olen = (size_t)(AL(in) * orate / irate + .5);    
  float * out = malloc(sizeof(*out) * olen);        
  size_t odone;

  soxr_error_t error = soxr_oneshot(irate, orate, 1,  
      in, AL(in), NULL,                               
      out, olen, &odone,                              
      NULL, NULL, NULL);                              

  unsigned i = 0;                             
  while (i++ < odone)
    printf("%5.2f%c", out[i-1], " \n"[!(i&7) || i == odone]);
  printf("%-26s %s\n", arg[0], soxr_strerror(error));  

  if (argc > 3)                                      
    printf("runtime=%s API="SOXR_THIS_VERSION_STR"\n", soxr_version());

  free(out);                                                       
  return !!error;
}
