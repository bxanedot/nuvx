









if (AVUTIL_INCLUDE_DIRS)
  set (AVUTIL_FIND_QUIETLY TRUE)
endif ()

find_path (AVUTIL_INCLUDE_DIRS libavutil/cpu.h)

find_library (AVUTIL_LIBRARIES NAMES avutil)

include (FindPackageHandleStandardArgs)
find_package_handle_standard_args (
  AVUTIL DEFAULT_MSG AVUTIL_LIBRARIES AVUTIL_INCLUDE_DIRS)

mark_as_advanced (AVUTIL_LIBRARIES AVUTIL_INCLUDE_DIRS)
