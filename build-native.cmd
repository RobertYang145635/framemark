@echo off
setlocal
rem Keep the entry point relocatable; all implementation lives in this checkout.
if defined FM_NODE_EXE (
  "%FM_NODE_EXE%" "%~dp0scripts\native\build.cjs" %*
) else (
  node "%~dp0scripts\native\build.cjs" %*
)
exit /b %ERRORLEVEL%
