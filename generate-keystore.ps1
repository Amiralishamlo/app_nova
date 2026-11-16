# اسکریپت ساخت کلید امضای دیجیتال (Keystore) برای نسخه نهایی اپلیکیشن نوا سینما
$ErrorActionPreference = "Stop"

$keystoreFile = "novasinema-release.jks"
$alias = "novasinema"
$password = "NovaSinema@2026"

Write-Host "در حال ساخت فایل امضای دیجیتال (Keystore) برای نوا سینما..." -ForegroundColor Cyan

if (Test-Path $keystoreFile) {
    Write-Host "فایل $keystoreFile از قبل وجود دارد." -ForegroundColor Yellow
} else {
    keytool -genkeypair -v `
        -keystore $keystoreFile `
        -alias $alias `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -storepass $password `
        -keypass $password `
        -dname "CN=NovaSinema, OU=Media, O=NovaSinema, L=Tehran, ST=Tehran, C=IR"
}

$propsContent = @"
storeFile=$keystoreFile
storePassword=$password
keyAlias=$alias
keyPassword=$password
"@

Set-Content -Path "keystore.properties" -Value $propsContent -Encoding UTF8
Write-Host "فایل keystore.properties با موفقیت ساخته شد! اکنون نسخه Release به صورت خودکار امضا می‌شود." -ForegroundColor Green
