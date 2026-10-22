# Build toan bo du an va chay unit test (khong can MySQL).
#   .\scripts\build.ps1            build + test
#   .\scripts\build.ps1 -SkipTests chi build
param([switch] $SkipTests)
. "$PSScriptRoot\_common.ps1"
if ($SkipTests) { Invoke-Mvn -q clean install '-DskipTests' } else { Invoke-Mvn clean install }
Write-Host 'Build xong.' -ForegroundColor Green
