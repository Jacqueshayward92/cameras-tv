# Tests the RTSP client on this PC against a fake recorder, using made-up logins and real encoded video.
param([string]$Project)
$T = "$env:LOCALAPPDATA\Temp\camb"
$W = "$T\test"
New-Item -ItemType Directory -Force "$W\classes" | Out-Null
$jdk = (Get-ChildItem "$T\android-tools\jdk" -Directory | Select-Object -First 1).FullName
$py = "$Project\tools\test\fake_recorder.py"
$user = 'testuser'
$pass = 'te st:p@ss/1'

& "$jdk\bin\javac.exe" -encoding UTF-8 --release 8 -nowarn -d "$W\classes" "$Project\app\src\main\java\za\schoolstreet\cameras\RtspSession.java" "$Project\tools\test\za\schoolstreet\cameras\ClientTest.java" 2>&1 | Where-Object { "$_" -notmatch 'warning|^\d+ warning' } | ForEach-Object { "$_" }

cmd /c "ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc=size=1280x720:rate=15 -t 4 -pix_fmt yuv420p -c:v libx264 -g 15 -bf 0 -x264-params repeat-headers=1 -f h264 `"$W\in.h264`" 2>&1"
cmd /c "ffmpeg -hide_banner -loglevel error -y -f lavfi -i testsrc=size=1280x720:rate=15 -t 4 -pix_fmt yuv420p -c:v libx265 -g 15 -bf 0 -x265-params repeat-headers=1:log-level=error -f hevc `"$W\in.h265`" 2>&1"
"Encoded: h264 $((Get-Item "$W\in.h264").Length) bytes, h265 $((Get-Item "$W\in.h265").Length) bytes"

function Test-Case($name, $port, $codec, $video, $sprop, $clientUser, $clientPass, $wanted) {
    "=== $name"
    $server = Start-Process python -ArgumentList @('-I', "`"$py`"", $port, $codec, "`"$video`"", $user, "`"$pass`"", $sprop) -PassThru -WindowStyle Hidden -RedirectStandardOutput "$W\server-$port.log" -RedirectStandardError "$W\server-$port.err"
    Start-Sleep -Seconds 2
    $out = "$W\out-$port.$codec"
    & "$jdk\bin\java.exe" -cp "$W\classes" za.schoolstreet.cameras.ClientTest $port $clientUser $clientPass $out $wanted 2>&1 | ForEach-Object { "$_" }
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 300
    if (Test-Path "$W\server-$port.err") { Get-Content "$W\server-$port.err" | Select-Object -Last 5 }
    "server saw: " + ((Get-Content "$W\server-$port.log" -ErrorAction SilentlyContinue | Where-Object { $_ -like 'REQUEST*' } | ForEach-Object { ($_ -split ' ')[1] }) -join ' ')
    if ($wanted -gt 0 -and (Test-Path $out) -and (Get-Item $out).Length -gt 0) {
        $format = if ($codec -eq 'h265') { 'hevc' } else { 'h264' }
        $frames = cmd /c "ffprobe -v error -f $format -count_frames -select_streams v:0 -show_entries stream=codec_name,width,height,nb_read_frames -of csv=p=0 `"$out`" 2>&1"
        $errors = cmd /c "ffmpeg -hide_banner -v error -f $format -i `"$out`" -f null - 2>&1"
        "decoded: $frames; decode errors: $(@($errors).Where({ $_ }).Count)"
    }
}

Test-Case 'H.264, no setup data in description, correct login' 18554 h264 "$W\in.h264" nosprop $user $pass 90
Test-Case 'H.264, setup data in description, correct login' 18555 h264 "$W\in.h264" sprop $user $pass 90
Test-Case 'H.265, no setup data in description, correct login' 18556 h265 "$W\in.h265" nosprop $user $pass 90
Test-Case 'H.265, setup data in description, correct login' 18557 h265 "$W\in.h265" sprop $user $pass 90
Test-Case 'Wrong password must stop, not retry' 18558 h264 "$W\in.h264" nosprop $user 'wrong' 0
