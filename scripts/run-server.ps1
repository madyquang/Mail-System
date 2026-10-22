# Chay Mail Server (TLS 1.3, cong 5000 mac dinh).
# Lan dau: chay .\scripts\setup-db.ps1 truoc de tao database va config/server.properties.
#   .\scripts\run-server.ps1
#   .\scripts\run-server.ps1 -Port 5001
param([int] $Port = 0)
. "$PSScriptRoot\_common.ps1"
Invoke-Mvn -q install '-DskipTests'
$extra = @()
if ($Port -gt 0) { $extra += "-Dserver.port=$Port" }
Invoke-Mvn -q -f mail-server/pom.xml exec:java @extra
