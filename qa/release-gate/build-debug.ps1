$ErrorActionPreference = 'Stop'
. 'E:\FrameMark\execution-ui-v2-20261106\tools\env-uiv2.ps1'
$env:SKIA_BINARIES_URL = 'file://D:/dev/native-toolchain-cache/skia-binaries-ce6727ef251518df37bc-x86_64-pc-windows-msvc-d3d-ganesh-gl-jpegd-jpege-pdf-textlayout.tar.gz'
$cargo = 'D:\dev\cargo\bin\cargo.exe'
$target = 'E:\FrameMark\execution-ui-v2-20261106\build\runs\20261117055959864\artifacts\cargo\desktop'
$manifest = 'E:\FrameMark\work\ui-v2\engine\e2b\Cargo.toml'
cmd /d /s /c "call D:\dev\vs2022bt\Common7\Tools\VsDevCmd.bat -arch=amd64 -host_arch=amd64 >nul && `"$cargo`" build --release --manifest-path `"$manifest`" --target-dir `"$target`" --target x86_64-pc-windows-msvc"
exit $LASTEXITCODE
