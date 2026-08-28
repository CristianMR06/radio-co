<#
.SYNOPSIS
  Crea el repositorio publico en GitHub, sube el codigo y publica la primera
  release con el APK. Solo hay que ejecutarlo una vez.

.NOTES
  Antes hay que estar logueado:  gh auth login
  Ejecutar desde una consola normal de Windows, no desde Claude Desktop.
#>
param(
    [string]$Repo = "radio-co"
)

$ErrorActionPreference = "Stop"
$raiz    = $PSScriptRoot
$android = Join-Path $raiz "android"
$gradleFile = Join-Path $android "app\build.gradle.kts"

. (Join-Path $PSScriptRoot "lib-release.ps1")
Add-HerramientasAlPath

# --- 1. quien soy en GitHub --------------------------------------------------
$login = (gh api user --jq .login 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($login)) {
    Write-Host "No estas logueado en GitHub. Ejecuta primero:" -ForegroundColor Yellow
    Write-Host "    gh auth login" -ForegroundColor Yellow
    exit 1
}
$slug = "$login/$Repo"
Write-Host "Cuenta de GitHub: $login   ->   repositorio: $slug" -ForegroundColor Cyan

# --- 2. que el APK apunte al repositorio correcto ----------------------------
$recompilar = $false
$texto = Get-Content $gradleFile -Raw -Encoding UTF8
if (-not ($texto -match 'GITHUB_REPO",\s*"\\"([^\\]+)\\""')) {
    throw "No encuentro GITHUB_REPO en $gradleFile"
}
$actual = $Matches[1]
if ($actual -ne $slug) {
    Write-Host "Corrijo GITHUB_REPO: $actual -> $slug" -ForegroundColor Yellow
    $texto = $texto -replace [regex]::Escape($actual), $slug
    Set-Content -Path $gradleFile -Value $texto -Encoding UTF8 -NoNewline
    Push-Location $raiz
    git add -A
    git commit -m "Apunta las actualizaciones a $slug"
    Pop-Location
    $recompilar = $true
}

# --- 3. crear el repo y subir ------------------------------------------------
Push-Location $raiz
try {
    $existe = gh repo view $slug --json name 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "El repositorio ya existe, solo subo los cambios." -ForegroundColor Yellow
        git remote get-url origin 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { git remote add origin "https://github.com/$slug.git" }
        git push -u origin main
    } else {
        gh repo create $slug --public --source=. --remote=origin --push `
            --description "Radio sin publicidad: Olimpica Stereo Ibague y La Mega Bogota"
        if ($LASTEXITCODE -ne 0) { throw "gh repo create fallo" }
    }
} finally {
    Pop-Location
}

# --- 4. compilar (si hubo que cambiar el slug) y publicar la release ---------
$env:JAVA_HOME    = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

if ($recompilar) {
    Push-Location $android
    try {
        & .\gradlew.bat assembleRelease --console=plain
        if ($LASTEXITCODE -ne 0) { throw "La compilacion fallo" }
    } finally { Pop-Location }
    Push-Location $raiz; git push; Pop-Location
}

if ($texto -match 'versionCode\s*=\s*(\d+)')  { $codigo  = [int]$Matches[1] } else { throw "sin versionCode" }
if ($texto -match 'versionName\s*=\s*"([^"]*)"') { $version = $Matches[1] }  else { throw "sin versionName" }

$apkOrigen  = Join-Path $android "app\build\outputs\apk\release\app-release.apk"
$apkDestino = Join-Path $android "app\build\outputs\apk\release\radioco-$codigo.apk"
if (-not (Test-Path $apkOrigen)) { throw "No encuentro el APK. Compila primero con android\build-apk.bat" }
Copy-Item $apkOrigen $apkDestino -Force

Push-Location $raiz
try {
    Publish-Release -Tag "v$codigo" -Titulo "Radio CO $version" -Apk $apkDestino `
        -Notas "Primera version publicada. Boton de actualizacion dentro de la app."
} finally { Pop-Location }

Write-Host ""
Write-Host "Listo: https://github.com/$slug" -ForegroundColor Green
Write-Host "APK de la release: https://github.com/$slug/releases/latest" -ForegroundColor Green
Write-Host ""
Write-Host "En el movil: desinstala la version anterior (esta firmada con otra clave)" -ForegroundColor Yellow
Write-Host "e instala este APK. A partir de ahi, todo desde el boton de la app." -ForegroundColor Yellow
