param(
  [string]$OutDir = "out/pg-migration",
  [string]$Prefix = ""
)

$ErrorActionPreference = "Stop"

$argsList = @("--out", $OutDir)
if ($Prefix -ne "") {
  $argsList += @("--prefix", $Prefix)
}

python scripts/export_existing_to_pg.py @argsList

