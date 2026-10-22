# Kiem thu END-TO-END voi MySQL that: khoi dong mot Server rieng (cong 5098, thu muc du lieu tam),
# chay ~50 kich ban qua TLS (EndToEndCheck), roi tat Server va xoa du lieu kiem thu.
# Can config/server.properties (xem setup-db.ps1).
param([int] $Port = 5098)
. "$PSScriptRoot\_common.ps1"
Invoke-Mvn -q install '-DskipTests'
$cpFile = Join-Path $env:TEMP 'mail-e2e-classpath.txt'
Invoke-Mvn -q -f mail-server/pom.xml dependency:build-classpath "-Dmdep.outputFile=$cpFile"
$classpath = (Get-Content $cpFile) + ';' + (Join-Path $RepoRoot 'mail-server\target\classes')
$dataDir = Join-Path $env:TEMP ('mail-e2e-' + [guid]::NewGuid())
$server = Start-Process java -WorkingDirectory $RepoRoot -NoNewWindow -PassThru -ArgumentList @(
    "-Dserver.port=$Port", "-Ddata.dir=`"$dataDir`"", '-cp', "`"$classpath`"", 'com.mailsystem.server.MailServerApplication')
try {
    $ready = $false
    for ($i = 0; $i -lt 60 -and -not $ready; $i++) {
        Start-Sleep -Milliseconds 500
        $ready = Test-NetConnection localhost -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue
    }
    if (-not $ready) { throw "Server khong mo cong $Port (xem log phia tren)." }
    & java -cp $classpath com.mailsystem.server.tools.EndToEndCheck $Port
    $code = $LASTEXITCODE
} finally {
    Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $dataDir -ErrorAction SilentlyContinue
}
exit $code
