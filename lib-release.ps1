# Funciones compartidas por subir-a-github.ps1 y publicar-actualizacion.ps1.
#
# Por que existe este fichero: en PowerShell 5.1 no se puede confiar en el
# codigo de salida de gh/git. Escriben mensajes normales por stderr y
# $LASTEXITCODE acaba mintiendo (paso: la release se creo bien y el script
# aborto igualmente). Aqui se comprueba el estado real consultando a GitHub.

function Add-HerramientasAlPath {
    # Ni git ni gh estan siempre en el PATH del sistema en esta maquina.
    foreach ($p in @("C:\Program Files\Git\cmd", "C:\Program Files\GitHub CLI")) {
        if ((Test-Path $p) -and ($env:PATH -notlike "*$p*")) { $env:PATH = "$p;$env:PATH" }
    }
}

function Test-ReleaseExiste {
    param([Parameter(Mandatory)][string]$Tag)
    $null = gh release view $Tag --json tagName 2>$null
    return ($LASTEXITCODE -eq 0)
}

<#
.SYNOPSIS
  Publica (o actualiza) una release y comprueba de verdad que el APK quedo
  colgado, en vez de fiarse del codigo de salida de gh.
#>
function Publish-Release {
    param(
        [Parameter(Mandatory)][string]$Tag,
        [Parameter(Mandatory)][string]$Titulo,
        [Parameter(Mandatory)][string]$Notas,
        [Parameter(Mandatory)][string]$Apk
    )

    if (-not (Test-Path $Apk)) { throw "No encuentro el APK: $Apk" }
    $nombreApk = Split-Path $Apk -Leaf

    # el ErrorActionPreference global haria saltar a gh por escribir en stderr
    $previo = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if (Test-ReleaseExiste -Tag $Tag) {
            Write-Host "La release $Tag ya existe: reemplazo el APK." -ForegroundColor Yellow
            gh release upload $Tag $Apk --clobber
        } else {
            gh release create $Tag $Apk --title $Titulo --notes $Notas
        }
    } finally {
        $ErrorActionPreference = $previo
    }

    # la unica comprobacion que vale: preguntarle a GitHub como quedo
    $crudo = gh release view $Tag --json tagName,assets 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($crudo)) {
        throw "La release $Tag no existe en GitHub. Mira la salida de gh mas arriba."
    }
    $info = $crudo | ConvertFrom-Json
    if ($info.assets.name -notcontains $nombreApk) {
        throw "La release $Tag existe pero sin $nombreApk. Sube el APK a mano o vuelve a lanzar el script."
    }

    Write-Host "Release $Tag publicada con $nombreApk" -ForegroundColor Green
}
