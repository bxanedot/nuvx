




#include <soxr-lsr.h>
#include "examples-common.h"

float in[] = {   
  0,1,0,-1, 0,1,0,-1, 0,1,0,-1, 0,1,0,-1, 0,1,0,-1, 0,1,0,-1,
  0,1,0,-1, 0,1,0,-1, 0,1,0,-1, 0,1,0,-1, 0,1,0,-1, 0,1,0,-1};

int main(int argc, char const * arg[])
{
  double irate = argc > 1? atof(arg[1]) : 1;       
  double orate = argc > 2? atof(arg[2]) : 2;              

  size_t olen = (size_t)(AL(in) * orate / irate + .5);    
  float * out = (float *)malloc(sizeof(*out) * olen);  

  int error, i = 0;
  SRC_DATA data;

  data.data_in = in;
  data.data_out = out;
  data.input_frames = AL(in);
  data.output_frames = (int)olen;
  data.src_ratio = orate / irate;
  error = src_simple(&data, SRC_SINC_FASTEST, 1);

  while (i++ < data.output_frames_gen)        
    printf("%5.2f%c", out[i-1], " \n"[!(i&7) || i == data.output_frames_gen]);
  printf("%-26s %s\n", arg[0], src_strerror(error));   

  if (argc > 3)                                      
    printf("runtime=%s\n", src_get_version());

  free(out);                                                       
  return !!error;
}
