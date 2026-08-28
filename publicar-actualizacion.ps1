<#
.SYNOPSIS
  Publica una version nueva de Radio CO: sube el versionCode, compila el APK
  firmado y crea la release en GitHub. El boton "Buscar actualizaciones" de la
  app la ve en cuanto termina.

.EXAMPLE
  .\publicar-actualizacion.ps1 -Version "1.2" -Notas "Anadida Olimpica Bogota"

.NOTES
  Ejecutar desde una consola normal de Windows (no desde Claude Desktop):
  Gradle no arranca ahi. Requiere 'gh auth login' hecho una vez.
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Notas = "",
    [switch]$SoloCompilar
)

$ErrorActionPreference = "Stop"
$raiz    = $PSScriptRoot
$android = Join-Path $raiz "android"
$gradleFile = Join-Path $android "app\build.gradle.kts"

. (Join-Path $raiz "lib-release.ps1")
Add-HerramientasAlPath

# --- 1. subir el versionCode -------------------------------------------------
$original = Get-Content $gradleFile -Raw -Encoding UTF8
if (-not ($original -match 'versionCode\s*=\s*(\d+)')) {
    throw "No encuentro versionCode en $gradleFile"
}
$codigoActual = [int]$Matches[1]
$codigoNuevo  = $codigoActual + 1

$texto = $original -replace 'versionCode\s*=\s*\d+', "versionCode = $codigoNuevo"
$texto = $texto    -replace 'versionName\s*=\s*"[^"]*"', "versionName = `"$Version`""
Set-Content -Path $gradleFile -Value $texto -Encoding UTF8 -NoNewline

Write-Host "versionCode $codigoActual -> $codigoNuevo   versionName -> $Version" -ForegroundColor Cyan

# --- 2. compilar -------------------------------------------------------------
$env:JAVA_HOME    = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

try {
    Push-Location $android
    try {
        & .\gradlew.bat assembleRelease --console=plain
        if ($LASTEXITCODE -ne 0) { throw "La compilacion fallo" }
    } finally {
        Pop-Location
    }
} catch {
    # dejar el proyecto como estaba: si no compila, no queremos la version subida
    Set-Content -Path $gradleFile -Value $original -Encoding UTF8 -NoNewline
    Write-Host "Compilacion fallida: he devuelto el versionCode a $codigoActual" -ForegroundColor Yellow
    throw
}

$apkOrigen  = Join-Path $android "app\build\outputs\apk\release\app-release.apk"
$apkDestino = Join-Path $android "app\build\outputs\apk\release\radioco-$codigoNuevo.apk"
Copy-Item $apkOrigen $apkDestino -Force

$tam = [math]::Round((Get-Item $apkDestino).Length / 1MB, 1)
Write-Host "APK listo: $apkDestino ($tam MB)" -ForegroundColor Green

if ($SoloCompilar) {
    Write-Host "-SoloCompilar: no publico nada en GitHub." -ForegroundColor Yellow
    exit 0
}

# --- 3. publicar -------------------------------------------------------------
# La etiqueta TIENE que ser v<versionCode>: es lo que compara la app.
$tag = "v$codigoNuevo"
if ([string]::IsNullOrWhiteSpace($Notas)) { $Notas = "Version $Version" }

Push-Location $raiz
try {
    $previo = $ErrorActionPreference
    $ErrorActionPreference = "Continue"   # git escribe su progreso por stderr
    try {
        git add -A
        git commit -m "Version $Version (versionCode $codigoNuevo)"
        git push
    } finally {
        $ErrorActionPreference = $previo
    }

    # comprobar que el commit esta de verdad en GitHub antes de etiquetar
    $local  = (git rev-parse HEAD).Trim()
    $remoto = (git rev-parse origin/main).Trim()
    if ($local -ne $remoto) {
        throw "El push no llego: local $($local.Substring(0,7)) vs origin/main $($remoto.Substring(0,7))"
    }

    Publish-Release -Tag $tag -Titulo "Radio CO $Version" -Notas $Notas -Apk $apkDestino
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "Publicada la $tag. Abre la app y pulsa 'Buscar actualizaciones'." -ForegroundColor Green
