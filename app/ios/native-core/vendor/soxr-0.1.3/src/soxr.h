




















#if !defined soxr_included
#define soxr_included


#if defined __cplusplus
  #include <cstddef>
  extern "C" {
#else
  #include <stddef.h>
#endif

#if defined SOXR_DLL
  #if defined soxr_EXPORTS
    #define SOXR __declspec(dllexport)
  #else
    #define SOXR __declspec(dllimport)
  #endif
#elif defined SOXR_VISIBILITY && defined __GNUC__ && (__GNUC__ > 4 || __GNUC__ == 4 && __GNUC_MINOR__ >= 1)
  #define SOXR __attribute__ ((visibility("default")))
#else
  #define SOXR
#endif

typedef struct soxr_io_spec soxr_io_spec_t;
typedef struct soxr_quality_spec soxr_quality_spec_t;
typedef struct soxr_runtime_spec soxr_runtime_spec_t;



















#define SOXR_VERSION(x,y,z)     (((x)<<16)|((y)<<8)|(z))
#define SOXR_THIS_VERSION       SOXR_VERSION(0,1,3)
#define SOXR_THIS_VERSION_STR               "0.1.3"





typedef struct soxr * soxr_t;           
typedef char const * soxr_error_t;                 

typedef void       * soxr_buf_t;   
typedef void const * soxr_cbuf_t;                         

typedef soxr_buf_t const  * soxr_bufs_t; 
typedef soxr_cbuf_t const * soxr_cbufs_t;                 

typedef void const * soxr_in_t;       

typedef void       * soxr_out_t;      






SOXR char const * soxr_version(void);   

#define soxr_strerror(e)                     \
    ((e)?(e):"no error")




SOXR soxr_t soxr_create(
    double      input_rate,       
    double      output_rate,      
    unsigned    num_channels,     

    soxr_error_t *,               
    soxr_io_spec_t const *,       
    soxr_quality_spec_t const *,  
    soxr_runtime_spec_t const *); 










SOXR soxr_error_t soxr_process(
    soxr_t      resampler,       

    soxr_in_t   in,              
    size_t      ilen,            
    size_t      * idone,         

    soxr_out_t  out,             
    size_t      olen,            
    size_t      * odone);        









typedef size_t  
  (* soxr_input_fn_t)(          
    void * input_fn_state,      
    soxr_in_t * data,           
    size_t requested_len);      












SOXR soxr_error_t soxr_set_input_fn( 
    soxr_t resampler,             
    soxr_input_fn_t,              
    void * input_fn_state,        
    size_t max_ilen);             



SOXR size_t   soxr_output( 
    soxr_t resampler,             
    soxr_out_t data,              
    size_t olen);                 





SOXR soxr_error_t soxr_error(soxr_t);    
SOXR size_t   * soxr_num_clips(soxr_t);  
SOXR double     soxr_delay(soxr_t);   
SOXR char const * soxr_engine(soxr_t);   

SOXR soxr_error_t soxr_clear(soxr_t);  
SOXR void         soxr_delete(soxr_t);   








SOXR soxr_error_t soxr_oneshot(
    double         input_rate,
    double         output_rate,
    unsigned       num_channels,
    soxr_in_t    in , size_t ilen, size_t * idone,
    soxr_out_t   out, size_t olen, size_t * odone,
    soxr_io_spec_t const *,
    soxr_quality_spec_t const *,
    soxr_runtime_spec_t const *);






SOXR soxr_error_t soxr_set_io_ratio(soxr_t, double io_ratio, size_t slew_len);





typedef enum {           

  SOXR_FLOAT32, SOXR_FLOAT64, SOXR_INT32, SOXR_INT16, SOXR_SPLIT = 4,


  SOXR_FLOAT32_I = SOXR_FLOAT32, SOXR_FLOAT64_I, SOXR_INT32_I, SOXR_INT16_I,


  SOXR_FLOAT32_S = SOXR_SPLIT  , SOXR_FLOAT64_S, SOXR_INT32_S, SOXR_INT16_S

} soxr_datatype_t;

#define soxr_datatype_size(x)   \
  ((unsigned char *)"\4\10\4\2")[(x)&3]



struct soxr_io_spec {                                             
  soxr_datatype_t itype;      
  soxr_datatype_t otype;      
  double scale;               
  void * e;                   
  unsigned long flags;        
};

#define SOXR_TPDF              0      
#define SOXR_NO_DITHER         8u     



struct soxr_quality_spec {                                        
  double precision;          
  double phase_response;     
  double passband_end;       
  double stopband_begin;     
  void * e;                  
  unsigned long flags;       
};

#define SOXR_ROLLOFF_SMALL     0u     
#define SOXR_ROLLOFF_MEDIUM    1u     
#define SOXR_ROLLOFF_NONE      2u     

#define SOXR_HI_PREC_CLOCK     8u   
#define SOXR_DOUBLE_PRECISION 16u   
#define SOXR_VR               32u   



struct soxr_runtime_spec {                                        
  unsigned log2_min_dft_size;    
  unsigned log2_large_dft_size;  
  unsigned coef_size_kbytes;     
  unsigned num_threads;          
  void * e;                      
  unsigned long flags;           
};

#define SOXR_COEF_INTERP_AUTO  0u     
#define SOXR_COEF_INTERP_LOW   2u     
#define SOXR_COEF_INTERP_HIGH  3u     









SOXR soxr_quality_spec_t soxr_quality_spec(
    unsigned long recipe,        
    unsigned long flags);        


#define SOXR_QQ                 0    
#define SOXR_LQ                 1    
#define SOXR_MQ                 2    
#define SOXR_HQ                 SOXR_20_BITQ  
#define SOXR_VHQ                SOXR_28_BITQ  

#define SOXR_16_BITQ            3
#define SOXR_20_BITQ            4
#define SOXR_24_BITQ            5
#define SOXR_28_BITQ            6
#define SOXR_32_BITQ            7

#define SOXR_LSR0Q              8      
#define SOXR_LSR1Q              9      
#define SOXR_LSR2Q              10     

#define SOXR_LINEAR_PHASE       0x00
#define SOXR_INTERMEDIATE_PHASE 0x10
#define SOXR_MINIMUM_PHASE      0x30

#define SOXR_STEEP_FILTER       0x40



SOXR soxr_runtime_spec_t soxr_runtime_spec(
    unsigned num_threads);



SOXR soxr_io_spec_t soxr_io_spec(
    soxr_datatype_t itype,
    soxr_datatype_t otype);

















SOXR soxr_error_t soxr_set_error(soxr_t, soxr_error_t);
SOXR soxr_error_t soxr_set_num_channels(soxr_t, unsigned);



#undef SOXR

#if defined __cplusplus
}
#endif

#endif
