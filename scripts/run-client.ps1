# Mo mot cua so Mail Client (JavaFX). Chay nhieu lan de mo nhieu client.
#   .\scripts\run-client.ps1
#   .\scripts\run-client.ps1 -Server 192.168.1.10:5000
param([string] $Server = '')
. "$PSScriptRoot\_common.ps1"
if ($Server) {
    $parts = $Server.Split(':')
    $env:MAIL_SERVER_HOST = $parts[0]
    if ($parts.Length -gt 1) { $env:MAIL_SERVER_PORT = $parts[1] }
}
if (-not (Test-Path 'mail-client\target\classes')) { Invoke-Mvn -q install '-DskipTests' }
Invoke-Mvn -q -f mail-client/pom.xml javafx:run
