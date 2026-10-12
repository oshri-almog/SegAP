# Builds the native helper(s) into the app's jniLibs (packaged as lib*.so so Android extracts them to disk).
# Requires the Android NDK (r27d) under $env:LOCALAPPDATA\Android\Sdk\ndk-extract.
$ErrorActionPreference = 'Stop'
$ndk = Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk-extract\android-ndk-r27d'
$cc = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android30-clang.cmd'
$out = Join-Path $PSScriptRoot '..\app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force $out | Out-Null
& $cc -O2 -Wall -Wextra -fPIE -pie -s -o (Join-Path $out 'libmhwl.so') (Join-Path $PSScriptRoot 'mhwl.c')
if ($LASTEXITCODE -ne 0) { throw "compile failed" }
Get-Item (Join-Path $out 'libmhwl.so') | Select-Object Name, Length
