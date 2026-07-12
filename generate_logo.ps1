Add-Type -AssemblyName System.Drawing

$width = 16
$height = 16
$bmp = New-Object System.Drawing.Bitmap($width, $height)

# Colors
$border = [System.Drawing.Color]::FromArgb(255, 45, 48, 51) # Sleek slate border
$water  = [System.Drawing.Color]::FromArgb(255, 34, 117, 221) # Minecraft water blue
$grass  = [System.Drawing.Color]::FromArgb(255, 94, 166, 54) # Grass green
$mountain = [System.Drawing.Color]::FromArgb(255, 120, 110, 95) # Mountain gray/brown
$pointer = [System.Drawing.Color]::FromArgb(255, 0, 200, 255) # Cyan pointer
$sand = [System.Drawing.Color]::FromArgb(255, 218, 198, 137) # Sand/beach

# Grid representation (16 rows of 16 characters)
# B = Border, W = Water, G = Grass, M = Mountain, P = Pointer, S = Sand
$grid = @(
    "BBBBBBBBBBBBBBBB",
    "BGGGGGGGGGGGGWSB",
    "BGGGGGGGGGGGWWWB",
    "BGGGGGGGGGGWWWWB",
    "BGGGGGGGGGGWWWWB",
    "BGGGGGGGGWWWWWWB",
    "BGGGGGGGWWWWWWWB",
    "BGMMGGGGWWWPWWWB",
    "BGMMMGGGWWWWWWWB",
    "BGGMMGGWWWWWWWWB",
    "BGGGGGGWWWWWWWWB",
    "BGGGGSSWWWWWWWWB",
    "BGGGSSSWWWWWWWWB",
    "BGGSSSWWWWWWWWWB",
    "BGGSSWWWWWWWWWWB",
    "BBBBBBBBBBBBBBBB"
)

for ($y = 0; $y -lt 16; $y++) {
    $row = $grid[$y]
    for ($x = 0; $x -lt 16; $x++) {
        $char = $row[$x]
        $color = switch ($char) {
            'B' { $border }
            'W' { $water }
            'G' { $grass }
            'M' { $mountain }
            'P' { $pointer }
            'S' { $sand }
            default { [System.Drawing.Color]::Black }
        }
        $bmp.SetPixel($x, $y, $color)
    }
}

$outputPath = "c:\Users\hunga\Documents\Coder\Minecraft\myMod\Simple Map\src\main\resources\logo.png"
$bmp.Save($outputPath, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host "Logo successfully written to $outputPath"
