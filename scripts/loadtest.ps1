# Kiem thu TAI: khoi dong mot Server rieng (cong 5097, thu muc du lieu tam, noi gioi han toc do)
# roi cho N client TLS dong thoi dang nhap, xem thu, gui thu trong S giay. In request/giay va do tre p50/p95/p99.
#   .\scripts\loadtest.ps1 -Clients 100 -Seconds 30
param([int] $Clients = 50, [int] $Seconds = 20, [int] $Port = 5097)
. "$PSScriptRoot\_common.ps1"
Invoke-Mvn -q install '-DskipTests'
$cpFile = Join-Path $env:TEMP 'mail-load-classpath.txt'
Invoke-Mvn -q -f mail-server/pom.xml dependency:build-classpath "-Dmdep.outputFile=$cpFile"
$classpath = (Get-Content $cpFile) + ';' + (Join-Path $RepoRoot 'mail-server\target\classes')
$dataDir = Join-Path $env:TEMP ('mail-load-' + [guid]::NewGuid())
$server = Start-Process java -WorkingDirectory $RepoRoot -NoNewWindow -PassThru -RedirectStandardOutput (Join-Path $env:TEMP 'mail-load-server.log') -ArgumentList @(
    "-Dserver.port=$Port", "-Ddata.dir=`"$dataDir`"", "-Dserver.maxClients=$($Clients + 20)",
    '-Dsecurity.requestsPerSecond=1000', '-Dsecurity.requestBurst=2000', '-Dsecurity.registrationsPerHourPerIp=100000',
    '-Dmonitor.enabled=false', "-Ddb.poolSize=20", '-cp', "`"$classpath`"", 'com.mailsystem.server.MailServerApplication')
try {
    $ready = $false
    for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
        Start-Sleep -Milliseconds 500
        $ready = Test-NetConnection localhost -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue
    }
    if (-not $ready) { throw "Server khong mo cong $Port." }
    & java -cp $classpath com.mailsystem.server.tools.LoadTest $Port $Clients $Seconds
    $code = $LASTEXITCODE
} finally {
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $dataDir -ErrorAction SilentlyContinue
}
exit $code
