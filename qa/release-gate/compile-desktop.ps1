$ErrorActionPreference = 'Stop'
. 'E:\FrameMark\execution-ui-v2-20261106\tools\env-uiv2.ps1'
& $FM_GRADLE -p 'E:\FrameMark\work\ui-v2\ui' :composeApp:compileKotlinDesktop --offline --no-daemon --console=plain
exit $LASTEXITCODE
