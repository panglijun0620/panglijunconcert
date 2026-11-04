[CmdletBinding()]
param(
    [string]$BatchId = "local-fixture",
    [string]$OutputRoot = "",
    [string]$ConcertInput = "",
    [string]$CommentInput = ""
)

$ErrorActionPreference = "Stop"
$moduleRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $moduleRoot "target\local-output"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputRoot)) {
    $OutputRoot = [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $OutputRoot))
}

function Get-JavaMajor([string]$JavaHome) {
    $java = Join-Path $JavaHome 'bin\java.exe'
    $output = & $env:ComSpec /d /s /c "`"$java`" -version 2>&1"
    if ($LASTEXITCODE -ne 0) {
        return 0
    }
    return [int](($output | Select-Object -First 1) -replace '.*version "([0-9]+).*', '$1')
}

$jdkCandidates = @(
    $env:JAVA_HOME,
    (Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '(^|[-_])17([._-]|$)' } |
        ForEach-Object FullName),
    'C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot',
    'C:\Program Files\Java\jdk-17'
) | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe')) }
$jdkHome = $jdkCandidates | Where-Object {
    (Get-JavaMajor $_) -eq 17
} | Select-Object -First 1
if (-not $jdkHome) {
    throw 'Java 17 was not found. Spark 3.5 with Hadoop 3.3 is not compatible with Java 24.'
}
$javaCommand = Join-Path $jdkHome 'bin\java.exe'
$env:JAVA_HOME = $jdkHome
$env:Path = "$(Join-Path $jdkHome 'bin');$env:Path"

$javaOpenOptions = @(
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
    "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED"
)
$env:MAVEN_OPTS = "$env:MAVEN_OPTS $($javaOpenOptions -join ' ')".Trim()
$env:SPARK_LOCAL_IP = "127.0.0.1"

if ([string]::IsNullOrWhiteSpace($ConcertInput)) {
    $ConcertInput = Join-Path $moduleRoot "src\test\resources\fixtures\concerts.csv"
}
if ([string]::IsNullOrWhiteSpace($CommentInput)) {
    $CommentInput = Join-Path $moduleRoot "src\test\resources\fixtures\comments.csv"
}
$concerts = (Resolve-Path -LiteralPath $ConcertInput).Path
$comments = (($CommentInput -split ',') | ForEach-Object {
    (Resolve-Path -LiteralPath $_.Trim()).Path
}) -join ','
$classpathFile = Join-Path $moduleRoot "target\local-classpath.txt"

Push-Location $moduleRoot
try {
    & mvn.cmd -Plocal -DskipTests compile dependency:build-classpath `
        "-Dmdep.outputFile=$classpathFile" `
        "-Dmdep.pathSeparator=;"
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to compile the local Spark fixture runner"
    }

    $runtimeClasspath = "$(Join-Path $moduleRoot 'target\classes');$(Get-Content -Raw $classpathFile)"
    $javaArguments = @(
        $javaOpenOptions
        "-cp", $runtimeClasspath,
        "cn.edu.pulseatlas.spark.ConcertBatchJob",
        "--batch-id", $BatchId,
        "--concert-input", $concerts,
        "--comment-input", $comments,
        "--output-root", $OutputRoot,
        "--master", "local[2]",
        "--risk-min-samples", "20"
    )
    & $javaCommand @javaArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Local Spark fixture run failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

Write-Host "Local fixture output: $OutputRoot"
