# DS-FTP Demo Script - Run in Terminal 1 (Receiver Side)
# This script runs the receiver for each demo scenario

Write-Host "============================================" -ForegroundColor Cyan
Write-Host "DS-FTP RECEIVER DEMO SCRIPT" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan

cd "c:\Users\aarav\OneDrive\Desktop\CP372 A2\Receiver"

Write-Host "`nDEMO 1: Stop-and-Wait (RN=0)" -ForegroundColor Yellow
Write-Host "Run in Sender terminal: java Sender 127.0.0.1 9876 9877 testfile.txt 1000" -ForegroundColor Green
Write-Host "Press Enter when ready to start Receiver..."
Read-Host
java Receiver 127.0.0.1 9877 9876 output_saw.txt 0

Write-Host "`n--------------------------------------------"
Write-Host "DEMO 2: Go-Back-N Window=20 (RN=0)" -ForegroundColor Yellow
Write-Host "Run in Sender terminal: java Sender 127.0.0.1 9876 9877 testfile.txt 1000 20" -ForegroundColor Green
Write-Host "Press Enter when ready to start Receiver..."
Read-Host
java Receiver 127.0.0.1 9877 9876 output_gbn.txt 0

Write-Host "`n--------------------------------------------"
Write-Host "DEMO 3: Stop-and-Wait with ACK Loss (RN=5)" -ForegroundColor Yellow
Write-Host "Run in Sender terminal: java Sender 127.0.0.1 9876 9877 testfile.txt 500" -ForegroundColor Green
Write-Host "Press Enter when ready to start Receiver..."
Read-Host
java Receiver 127.0.0.1 9877 9876 output_saw_loss.txt 5

Write-Host "`n--------------------------------------------"
Write-Host "DEMO 4: Go-Back-N with ACK Loss (RN=10)" -ForegroundColor Yellow
Write-Host "Run in Sender terminal: java Sender 127.0.0.1 9876 9877 testfile.txt 500 20" -ForegroundColor Green
Write-Host "Press Enter when ready to start Receiver..."
Read-Host
java Receiver 127.0.0.1 9877 9876 output_gbn_loss.txt 10

Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host "ALL DEMOS COMPLETE!" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
