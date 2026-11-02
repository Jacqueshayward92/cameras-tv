# Fetches the build tools into a short temporary folder (long paths break the Android tools).
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$T = "$env:LOCALAPPDATA\Temp\camb\android-tools"
New-Item -ItemType Directory -Force $T | Out-Null

function Fetch($url, $zip, $dest) {
    if (Test-Path $dest) { return }
    $file = Join-Path $T $zip
    & curl.exe -L --fail --silent --show-error -o $file $url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
    New-Item -ItemType Directory -Force $dest | Out-Null
    & tar.exe -xf $file -C $dest
    if ($LASTEXITCODE -ne 0) { throw "Unpack failed: $zip" }
    Remove-Item $file
    Write-Output "Ready: $zip"
}

Fetch 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' 'jdk.zip' "$T\jdk"
Fetch 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' 'cmdline.zip' "$T\sdk\cmdline-tools"
Fetch 'https://services.gradle.org/distributions/gradle-8.9-bin.zip' 'gradle.zip' "$T\gradle"

$env:JAVA_HOME = (Get-ChildItem "$T\jdk" -Directory | Select-Object -First 1).FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$sm = "$T\sdk\cmdline-tools\cmdline-tools\bin\sdkmanager.bat"
Set-Content "$T\yes.txt" ((1..40 | ForEach-Object { 'y' }) -join "`r`n") -Encoding ascii
$ErrorActionPreference = 'Continue'
cmd /c "`"$sm`" --sdk_root=`"$T\sdk`" --licenses < `"$T\yes.txt`" > NUL 2>&1"
cmd /c "`"$sm`" --sdk_root=`"$T\sdk`" platforms;android-34 build-tools;34.0.0 < `"$T\yes.txt`" > NUL 2>&1"
Write-Output "Platform: $((Get-ChildItem "$T\sdk\platforms").Name); build tools: $((Get-ChildItem "$T\sdk\build-tools").Name)"
Write-Output "Java: $env:JAVA_HOME"
