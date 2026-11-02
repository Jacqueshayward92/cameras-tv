# Tests the alert logic and the recorder link on this PC, using made-up logins.
param([string]$Project)
$T = "$env:LOCALAPPDATA\Temp\camb"
$W = "$T\alert-test"
Remove-Item $W -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$W\classes" | Out-Null
$jdk = (Get-ChildItem "$T\android-tools\jdk" -Directory | Select-Object -First 1).FullName
$src = "$Project\app\src\main\java\za\schoolstreet\cameras"
$tests = "$Project\tools\test\za\schoolstreet\cameras"

$compile = & "$jdk\bin\javac.exe" -encoding UTF-8 -nowarn -d "$W\classes" "$src\AlertParser.java" "$src\AlertPolicy.java" "$src\AlertServer.java" "$src\RecorderLink.java" "$tests\AlertTests.java" "$tests\LinkTest.java" 2>&1 | ForEach-Object { "$_" }
$compile | Where-Object { $_ -match 'error' -or $_ -match '\.java:' }

"=== Alert logic and listener"
& "$jdk\bin\java.exe" -cp "$W\classes" za.schoolstreet.cameras.AlertTests 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -notlike 'PASS*' }

"=== Recorder link"
cmd /c "openssl req -x509 -newkey rsa:2048 -nodes -keyout `"$W\key.pem`" -out `"$W\cert.pem`" -days 2 -subj /CN=localhost 2>NUL"
$user = 'testuser'
$pass = 'te st:p@ss/1'
$py = "$Project\tools\test\fake_openapi.py"

function Link-Case($name, $port, $mode, $initial, $clientPass, $address, $path, $expectResult, $expectInLog, $expectState) {
    Set-Content "$W\state.json" $initial -Encoding ascii -NoNewline
    $server = Start-Process python -ArgumentList @('-I', "`"$py`"", $port, "`"$W\cert.pem`"", "`"$W\key.pem`"", $user, "`"$pass`"", "`"$W\state.json`"", $mode) -PassThru -WindowStyle Hidden -RedirectStandardOutput "$W\s$port.log" -RedirectStandardError "$W\s$port.err"
    Start-Sleep -Seconds 2
    $result = & "$jdk\bin\java.exe" -cp "$W\classes" za.schoolstreet.cameras.LinkTest $port $user $clientPass $address $path 2>&1 | ForEach-Object { "$_" }
    Start-Sleep -Milliseconds 300
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 300
    $log = (Get-Content "$W\s$port.log" -ErrorAction SilentlyContinue) -join ' | '
    $state = Get-Content "$W\state.json" -Raw
    $ok = ("$result" -like $expectResult) -and ($log -like $expectInLog) -and ($state -like $expectState)
    "$(if ($ok) { 'PASS' } else { 'FAIL' }) $name"
    if (-not $ok) { "   result: $result"; "   log: $log"; "   state: $state"; Get-Content "$W\s$port.err" -ErrorAction SilentlyContinue | Select-Object -Last 4 }
}

Link-Case 'Empty recorder: adds this TV on slot 1' 18600 normal '[]' $pass '192.168.0.31' '/cameras-alert-aa' 'LINKED' '*TOKEN_GIVEN*ADDED*' '*"id": 1*"192.168.0.31"*8787*/cameras-alert-aa*HTTP*off*'
Link-Case 'Recorder that answers in percent-encoding' 18601 encoded '[]' $pass '192.168.0.31' '/cameras-alert-aa' 'LINKED' '*ADDED*' '*192.168.0.31*'
Link-Case 'Challenge sent with status 200, as in the document' 18602 status200 '[]' $pass '192.168.0.31' '/cameras-alert-aa' 'LINKED' '*ADDED*' '*192.168.0.31*'
Link-Case 'Recorder that wants the id as text' 18603 textid '[]' $pass '192.168.0.31' '/cameras-alert-aa' 'LINKED' '*ADD_REFUSED_NUMBER_ID*ADDED*' '*192.168.0.31*'
Link-Case 'Same TV, same address: changes nothing' 18604 normal '[{"id":2,"ip_or_domain":"192.168.0.31","port":8787,"url":"/cameras-alert-aa","protocol":"HTTP","picture_switch":"off"}]' $pass '192.168.0.31' '/cameras-alert-aa' 'LINKED' '*LIST' '*"id":2*192.168.0.31*'
Link-Case 'TV got a new address: updates its own slot' 18605 normal '[{"id":2,"ip_or_domain":"192.168.0.31","port":8787,"url":"/cameras-alert-aa","protocol":"HTTP","picture_switch":"off"}]' $pass '192.168.0.77' '/cameras-alert-aa' 'LINKED' '*DELETED 2*ADDED*' '*"id": 2*192.168.0.77*'
Link-Case 'Other servers are left alone' 18606 normal '[{"id":1,"ip_or_domain":"10.0.0.9","port":80,"url":"/other","protocol":"HTTP","picture_switch":"on"}]' $pass '192.168.0.31' '/cameras-alert-aa' 'LINKED' '*ADDED*' '*10.0.0.9*"id": 2*192.168.0.31*'
Link-Case 'All 3 slots taken: says so and removes nothing' 18607 normal '[{"id":1,"ip_or_domain":"a","port":1,"url":"/a"},{"id":2,"ip_or_domain":"b","port":1,"url":"/b"},{"id":3,"ip_or_domain":"c","port":1,"url":"/c"}]' $pass '192.168.0.31' '/cameras-alert-aa' 'ERROR*slots*' '*LIST' '*"id":3*'
Link-Case 'Wrong password: stops after one try' 18608 normal '[]' 'wrong' '192.168.0.31' '/cameras-alert-aa' 'LOGIN_REJECTED' 'TOKEN_CHALLENGE | TOKEN_REFUSED' '`[`]'
"Recorder offline:"
& "$jdk\bin\java.exe" -cp "$W\classes" za.schoolstreet.cameras.LinkTest 18699 $user $pass '192.168.0.31' '/x' 2>&1 | ForEach-Object { "   $_" }
