


math (EXPR quality "43 + (${bits} - 13) / 4")
set (ofile ${irate}-${orate}-${quality}.s32)


execute_process(COMMAND ${EXAMPLES_BIN}3-options-input-fn ${irate} ${orate} 1 2 2 ${quality} a
  INPUT_FILE ref-${irate}.s32
  OUTPUT_FILE ${ofile}
  ERROR_VARIABLE test_error
  RESULT_VARIABLE test_result)

if (test_result)
  message (FATAL_ERROR "Resampling failure: ${test_error}")
endif ()

set (percentageToCheck 98)
math (EXPR lenToCheck "${len} * ${percentageToCheck}")
string (REGEX REPLACE "(..)$" ".\\1" lenToCheck "${lenToCheck}")  

execute_process(COMMAND ${BIN}vector-cmp ref-${orate}.s32 ${ofile} ${orate} ${lenToSkip} ${lenToCheck} ${bits}
  OUTPUT_VARIABLE test_output
  RESULT_VARIABLE test_result)

if (test_result)
  message (FATAL_ERROR ${test_output})
else ()
  message (STATUS ${test_output})
endif ()
