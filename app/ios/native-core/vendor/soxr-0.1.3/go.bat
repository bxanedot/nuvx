@echo off



set build=%1
if x%build% == x set build=Release


del/f CMakeCache.txt

mkdir %build%
cd %build%

cmake -G "NMake Makefiles" -DCMAKE_BUILD_TYPE=%build% -Wno-dev ..
if errorlevel 1 goto end

nmake
if errorlevel 1 goto end

nmake test
if errorlevel 1 goto error
goto end

:error
echo FAILURE details in Testing\Temporary\LastTest.log

:end
