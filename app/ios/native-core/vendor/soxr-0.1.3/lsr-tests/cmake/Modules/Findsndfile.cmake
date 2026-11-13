









if (SNDFILE_INCLUDES)
  set (SNDFILE_FIND_QUIETLY TRUE)
endif (SNDFILE_INCLUDES)

find_path (SNDFILE_INCLUDES sndfile.h)

find_library (SNDFILE_LIBRARIES NAMES sndfile)

include (FindPackageHandleStandardArgs)
find_package_handle_standard_args (
  SNDFILE DEFAULT_MSG SNDFILE_LIBRARIES SNDFILE_INCLUDES)

mark_as_advanced (SNDFILE_LIBRARIES SNDFILE_INCLUDES)
