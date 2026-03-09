# DS-FTP Demo Script - Run in Terminal 2 (Sender Side)
# Copy and paste each command when prompted by the Receiver script

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "DS-FTP SENDER DEMO SCRIPT" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

cd "c:\Users\aarav\OneDrive\Desktop\CP372 A2\Sender"

Write-Host "`nCopy and run these commands one at a time:" -ForegroundColor Yellow
Write-Host ""
Write-Host "DEMO 1 (Stop-and-Wait):" -ForegroundColor Green
Write-Host "java Sender 127.0.0.1 9876 9877 testfile.txt 1000"
Write-Host ""
Write-Host "DEMO 2 (Go-Back-N):" -ForegroundColor Green  
Write-Host "java Sender 127.0.0.1 9876 9877 testfile.txt 1000 20"
Write-Host ""
Write-Host "DEMO 3 (Stop-and-Wait + ACK Loss):" -ForegroundColor Green
Write-Host "java Sender 127.0.0.1 9876 9877 testfile.txt 500"
Write-Host ""
Write-Host "DEMO 4 (Go-Back-N + ACK Loss):" -ForegroundColor Green
Write-Host "java Sender 127.0.0.1 9876 9877 testfile.txt 500 20"
Write-Host ""
Write-Host "VERIFY FILES MATCH:" -ForegroundColor Magenta
Write-Host 'Compare-Object (Get-Content testfile.txt) (Get-Content ..\Receiver\output_saw.txt)'
