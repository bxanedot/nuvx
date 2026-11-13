









if (AVCODEC_INCLUDE_DIRS)
  set (AVCODEC_FIND_QUIETLY TRUE)
endif ()

find_path (AVCODEC_INCLUDE_DIRS libavcodec/avcodec.h)

find_library (AVCODEC_LIBRARIES NAMES avcodec)

include (FindPackageHandleStandardArgs)
find_package_handle_standard_args (
  AVCODEC DEFAULT_MSG AVCODEC_LIBRARIES AVCODEC_INCLUDE_DIRS)

mark_as_advanced (AVCODEC_LIBRARIES AVCODEC_INCLUDE_DIRS)
