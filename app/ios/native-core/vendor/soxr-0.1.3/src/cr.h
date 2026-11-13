


#if !defined soxr_cr_included
#define soxr_cr_included

#define  FIFO_SIZE_T int
#include "fifo.h"

typedef void real;  
struct stage;
typedef void (* stage_fn_t)(struct stage * input, fifo_t * output);
typedef struct half_fir_info {
  int num_coefs;
  real const * coefs;
  stage_fn_t fn, dfn;
  float att;
} half_fir_info_t;
typedef struct {float scalar; stage_fn_t fn;} poly_fir1_t;
typedef struct {float beta; poly_fir1_t interp[3];} poly_fir_t;

#define U100_l 42
#define MULT32 (65536. * 65536.)


#define coef(coef_p, interp_order, fir_len, phase_num, coef_interp_num, fir_coef_num) (coef_p)[\
  (fir_len) * ((interp_order) + 1) * (phase_num) + \
  ((interp_order) + 1) * (fir_coef_num) + \
  ((interp_order) - (coef_interp_num))]


#define coef4(coef_p, interp_order, fir_len, phase_num, coef_interp_num, fir_coef_num) (coef_p)[\
  (fir_len) * ((interp_order) + 1) * (phase_num) + \
  ((interp_order) + 1) * ((fir_coef_num) & ~3) + \
  4 * ((interp_order) - (coef_interp_num)) + \
  ((fir_coef_num) & 3)]

typedef union {  
  #if HAVE_BIGENDIAN
  struct {int32_t ms; uint32_t ls;} parts;
  #else
  struct {uint32_t ls; int32_t ms;} parts;
  #endif
  int64_t all;
} int64p_t;

typedef union {  
  #if HAVE_BIGENDIAN
  struct {uint32_t ms, ls;} parts;
  #else
  struct {uint32_t ls, ms;} parts;
  #endif
  uint64_t all;
} uint64p_t;

typedef struct {
  int        dft_length, num_taps, post_peak;
  void       * dft_forward_setup, * dft_backward_setup;
  real   * coefs;
} dft_filter_t;

typedef struct {  
  real   * poly_fir_coefs;
  dft_filter_t dft_filter[2];
} rate_shared_t;

typedef double float_step_t;  

typedef union {  
  struct {uint64p_t ls; int64p_t ms;} fix;   
  float_step_t flt;
} step_t;

#define integer  fix.ms.parts.ms
#define fraction fix.ms.parts.ls
#define whole    fix.ms.all

#define CORE_DBL       1
#define CORE_SIMD_POLY 2
#define CORE_SIMD_HALF 4
#define CORE_SIMD_DFT  8
#define LOG2_SIZEOF_REAL(core_flags) (2 + ((core_flags) & 1))

typedef int core_flags_t;

#if defined SOXR_LIB
#include "rdft_t.h"
#else
typedef void fn_t;
#endif

typedef struct stage {
  int        num;


  core_flags_t   core_flags;
  stage_fn_t fn;
  fifo_t     fifo;
  int        pre;        
  int        pre_post;   
  int        preload;    
  double     out_in_ratio;  
  int        input_size;
  bool       is_input;


  fn_t const * rdft_cb;
  rate_shared_t * shared;
  unsigned   dft_filter_num;  
  real       * dft_scratch;
  float      * dft_out;
  real const * coefs;


  step_t     at, step;
  bool       use_hi_prec_clock;
  int        L, remM;
  int        n, phase_bits, block_len;
  double     mult, phase0;
} stage_t;

#define stage_occupancy(s) max(0, fifo_occupancy(&(s)->fifo) - (s)->pre_post)
#define stage_read_p(s) ((sample_t *)fifo_read_ptr(&(s)->fifo) + (s)->pre)

#define lq_bw0  (1385/2048.)  

typedef enum {rolloff_small, rolloff_medium, rolloff_none} rolloff_t;

typedef struct {
  void * (* alloc)(size_t);
  void * (* calloc)(size_t, size_t);
  void (* free)(void *);
} alloc_t;

typedef struct {
  alloc_t mem;
  half_fir_info_t  const * half_firs;
  size_t half_firs_len;
  half_fir_info_t  const * doub_firs;
  size_t doub_firs_len;
  stage_fn_t cubic_stage_fn;
  poly_fir_t const * poly_firs;
  fn_t * rdft_cb;
} cr_core_t;

typedef struct rate rate_t;
struct rate {
  cr_core_t const * core;
  double     io_ratio;
  int64_t    samples_in, samples_out;
  int        num_stages, flushing;
  stage_t    * stages;
};

#if defined SOXR_LIB

#include "soxr.h"

char const * _soxr_init(
  rate_t * const p,                 
  rate_shared_t * const shared,     
  double const io_ratio,            
  soxr_quality_spec_t const * const q_spec,
  soxr_runtime_spec_t const * const r_spec,
  double multiplier,                
  cr_core_t const * const core,
  core_flags_t const);

void _soxr_process(struct rate * p, size_t olen);
real * _soxr_input(struct rate * p, real const * samples, size_t n);
real const * _soxr_output(struct rate * p, real * samples, size_t * n0);
void _soxr_flush(struct rate * p);
void _soxr_close(struct rate * p);
double _soxr_delay(struct rate * p);
void _soxr_sizes(size_t * shared, size_t * channel);
#endif

#endif
