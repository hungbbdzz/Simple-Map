# Script to fetch vanilla Minecraft book texture and draw a clean question mark
Add-Type -AssemblyName System.Drawing

$destDir = "src/main/resources/assets/simplemap/textures/item"
if (!(Test-Path $destDir)) {
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
}

$tempPath = "$destDir/temp_clean_book.png"
$finalEmpty = "$destDir/empty_book_map.png"

# 1.21.1 official vanilla book texture URL
$vanillaBookUrl = "https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.1/assets/minecraft/textures/item/book.png"

Write-Host "Downloading clean vanilla book texture..."
Invoke-WebRequest -Uri $vanillaBookUrl -OutFile $tempPath

Write-Host "Drawing clean question mark on empty_book_map.png..."
$bmpEmpty = New-Object System.Drawing.Bitmap($tempPath)
$newEmpty = New-Object System.Drawing.Bitmap($bmpEmpty.Width, $bmpEmpty.Height)

# Copy original pixels
for ($y = 0; $y -lt $bmpEmpty.Height; $y++) {
    for ($x = 0; $x -lt $bmpEmpty.Width; $x++) {
        $c = $bmpEmpty.GetPixel($x, $y)
        $newEmpty.SetPixel($x, $y, $c)
    }
}
$bmpEmpty.Dispose()

# Palette
$cyan = [System.Drawing.Color]::FromArgb(255, 40, 180, 230)
$paper = [System.Drawing.Color]::FromArgb(255, 235, 215, 150)
$yellow = [System.Drawing.Color]::FromArgb(255, 240, 195, 60)

# Draw a golden question mark (?) in the center of the book cover (around x=5..9, y=4..10)
$newEmpty.SetPixel(6, 4, $yellow)
$newEmpty.SetPixel(7, 4, $yellow)
$newEmpty.SetPixel(8, 4, $yellow)
$newEmpty.SetPixel(5, 5, $yellow)
$newEmpty.SetPixel(9, 5, $yellow)
$newEmpty.SetPixel(9, 6, $yellow)
$newEmpty.SetPixel(8, 7, $yellow)
$newEmpty.SetPixel(7, 8, $yellow)
# Dot of the question mark
$newEmpty.SetPixel(7, 10, $yellow)

# Add blue bookmark ribbon
$newEmpty.SetPixel(9, 10, $cyan)
$newEmpty.SetPixel(10, 11, $cyan)

# Add map paper tabs at the bottom-left edges
$newEmpty.SetPixel(3, 9, $paper)
$newEmpty.SetPixel(4, 10, $paper)

# Save and overwrite
if (Test-Path $finalEmpty) { Remove-Item $finalEmpty -Force }
$newEmpty.Save($finalEmpty, [System.Drawing.Imaging.ImageFormat]::Png)

$newEmpty.Dispose()
Remove-Item $tempPath -Force

Write-Host "Successfully drawn a clean question mark on empty_book_map.png!"
