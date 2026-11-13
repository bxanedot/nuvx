

















#define	ABS(a)			(((a) < 0) ? - (a) : (a))
#define MIN(a,b)		(((a) < (b)) ? (a) : (b))
#define	MAX(a,b)		(((a) >= (b)) ? (a) : (b))

#define	ARRAY_LEN(x)	((int) (sizeof (x) / sizeof ((x) [0])))

void gen_windowed_sines (int freq_count, const double *freqs, double max, float *output, int output_len) ;

void save_oct_float (char *filename, float *input, int in_len, float *output, int out_len) ;
void save_oct_double (char *filename, double *input, int in_len, double *output, int out_len) ;

void interleave_data (const float *in, float *out, int frames, int channels) ;

void deinterleave_data (const float *in, float *out, int frames, int channels) ;

void reverse_data (float *data, int datalen) ;

double calculate_snr (float *data, int len, int expected_peaks) ;

const char * get_cpu_name (void) ;

#if OS_IS_WIN32







#define sleep _sleep
#endif

