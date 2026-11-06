@echo off
rem Сборка neurollm.dll (тот же llm_jni.cpp + llama.cpp) под Windows x64 для bench на ПК.
setlocal
set ROOT=%~dp0..
set VSDIR=C:\Program Files\Microsoft Visual Studio\18\Community
if not exist "%VSDIR%\VC\Auxiliary\Build\vcvars64.bat" set VSDIR=C:\Program Files\Microsoft Visual Studio\2022\Community
call "%VSDIR%\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
set CMAKE=C:\Users\leo\Android\Sdk\cmake\3.22.1\bin
if "%JAVA_HOME%"=="" set JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8
"%CMAKE%\cmake.exe" -S "%ROOT%\src\android\engine-llm\src\main\cpp" -B "%ROOT%\.cache\llm-win" -G Ninja ^
  -DCMAKE_MAKE_PROGRAM="%CMAKE%\ninja.exe" -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=cl -DCMAKE_CXX_COMPILER=cl || exit /b 1
"%CMAKE%\cmake.exe" --build "%ROOT%\.cache\llm-win" --config Release -j 16 || exit /b 1
echo built: %ROOT%\.cache\llm-win\neurollm.dll
