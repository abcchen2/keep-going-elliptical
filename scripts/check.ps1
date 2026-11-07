# Build + lint. Used by the Kiro "Build and lint after spec task" hook and handy locally.
# Picks a JDK 17+: $env:KGE_JAVA_HOME, then $env:JAVA_HOME if it is 17+, then common install paths.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)

function Test-Jdk17([string]$dir) {
    if (-not $dir -or -not (Test-Path "$dir\bin\java.exe")) { return $false }
    $ErrorActionPreference = 'Continue'   # java -version writes to stderr
    $v = (& "$dir\bin\java.exe" -version 2>&1 | Select-Object -First 1 | Out-String)
    return ($v -match '"(1[7-9]|[2-9]\d)')
}

$candidates = @($env:KGE_JAVA_HOME, $env:JAVA_HOME,
    'C:\Program Files\jdk17', 'C:\Program Files\jdk-21.0.4',
    'C:\Program Files\Android\Android Studio\jbr') +
    (Get-ChildItem 'C:\Program Files\Eclipse Adoptium', 'C:\Program Files\Java' -Directory -ErrorAction SilentlyContinue | ForEach-Object FullName)

$jdk = $candidates | Where-Object { Test-Jdk17 $_ } | Select-Object -First 1
if (-not $jdk) { Write-Error 'No JDK 17+ found. Set KGE_JAVA_HOME.'; exit 1 }
$env:JAVA_HOME = $jdk

& .\gradlew.bat assembleDebug lintDebug -q --console=plain
exit $LASTEXITCODE
