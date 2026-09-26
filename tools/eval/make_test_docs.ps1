# Renders synthetic, clearly-fake document images for evaluating Kairo's document pipeline.
# All names/numbers are dummy test data and every image carries a "SAMPLE - TEST DATA" watermark.
# Usage: powershell -ExecutionPolicy Bypass -File tools/eval/make_test_docs.ps1 -Out <folder>
param([string]$Out = "$PSScriptRoot\out")
Add-Type -AssemblyName System.Drawing
New-Item -ItemType Directory -Force $Out | Out-Null

function New-Doc([string]$file, [int]$w, [int]$h, [string]$bg, [object[]]$lines) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear([System.Drawing.ColorTranslator]::FromHtml($bg))
    foreach ($l in $lines) {
        # each line: @(text, x, y, size, bold, color)
        $style = if ($l[4]) { [System.Drawing.FontStyle]::Bold } else { [System.Drawing.FontStyle]::Regular }
        $font = New-Object System.Drawing.Font("Arial", [float]$l[3], $style, [System.Drawing.GraphicsUnit]::Pixel)
        $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml($l[5]))
        $g.DrawString($l[0], $font, $brush, [float]$l[1], [float]$l[2])
        $font.Dispose(); $brush.Dispose()
    }
    # Watermark
    $wm = New-Object System.Drawing.Font("Arial", [float]([Math]::Max(28, $w / 22)), [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $wb = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(60, 200, 0, 0))
    $g.TranslateTransform($w / 2, $h / 2); $g.RotateTransform(-25)
    $g.DrawString("SAMPLE - TEST DATA", $wm, $wb, [float](-$w / 4), [float]0)
    $g.ResetTransform(); $wm.Dispose(); $wb.Dispose()
    $bmp.Save((Join-Path $Out $file), [System.Drawing.Imaging.ImageFormat]::Jpeg)
    $g.Dispose(); $bmp.Dispose()
    "wrote $file"
}

$k = "#111111"
New-Doc "test_pan_card.jpg" 1012 638 "#dfeef7" @(
    @("INCOME TAX DEPARTMENT", 40, 30, 38, $true, "#0b3d91"),
    @("GOVT. OF INDIA", 700, 34, 30, $true, "#0b3d91"),
    @("Permanent Account Number Card", 40, 110, 30, $false, $k),
    @("ABCPT1234K", 40, 160, 52, $true, $k),
    @("Name", 40, 260, 22, $false, "#555555"),
    @("TEST USER KAIRO", 40, 290, 34, $true, $k),
    @("Father's Name", 40, 350, 22, $false, "#555555"),
    @("SAMPLE FATHER NAME", 40, 380, 30, $false, $k),
    @("Date of Birth", 40, 440, 22, $false, "#555555"),
    @("01/01/2000", 40, 470, 30, $false, $k)
)

New-Doc "test_restaurant_bill.jpg" 800 1300 "#ffffff" @(
    @("TEST BIRYANI HOUSE", 150, 40, 44, $true, $k),
    @("12 Sample Road, Hyderabad 500001", 150, 100, 24, $false, $k),
    @("GSTIN: 36AAAAA0000A1Z5", 150, 135, 24, $false, $k),
    @("TAX INVOICE", 280, 200, 34, $true, $k),
    @("Bill No: 4417      Date: 12/09/2026   Time: 21:14", 40, 270, 24, $false, $k),
    @("Item                     Qty     Amount", 40, 340, 26, $true, $k),
    @("Chicken Dum Biryani        2      640.00", 40, 390, 26, $false, $k),
    @("Paneer Tikka               1      320.00", 40, 430, 26, $false, $k),
    @("Double Ka Meetha           2      180.00", 40, 470, 26, $false, $k),
    @("Sub Total                        1140.00", 40, 560, 26, $false, $k),
    @("CGST 2.5%                          28.50", 40, 600, 26, $false, $k),
    @("SGST 2.5%                          28.50", 40, 640, 26, $false, $k),
    @("Service Charge                     48.50", 40, 680, 26, $false, $k),
    @("Grand Total          Rs. 1245.50", 40, 760, 34, $true, $k),
    @("Paid by UPI. Thank you, visit again!", 40, 860, 24, $false, $k)
)

New-Doc "test_upi_receipt.jpg" 1080 1900 "#f5f7fb" @(
    @("Payment successful", 300, 250, 52, $true, "#1b7f3b"),
    @("Rs. 350", 400, 360, 90, $true, $k),
    @("Paid to", 80, 560, 34, $false, "#555555"),
    @("Ravi Test Stores", 80, 610, 46, $true, $k),
    @("ravitest@upi", 80, 670, 32, $false, "#555555"),
    @("UPI transaction ID", 80, 800, 32, $false, "#555555"),
    @("425361789012", 80, 845, 42, $true, $k),
    @("Date: 18 Sep 2026, 7:42 PM", 80, 960, 34, $false, $k),
    @("From: Sample Bank ****1234", 80, 1030, 34, $false, $k)
)

New-Doc "test_train_ticket.jpg" 1240 1400 "#ffffff" @(
    @("IRCTC Electronic Reservation Slip (ERS)", 60, 40, 40, $true, "#0b3d91"),
    @("PNR No: 4521367890", 60, 130, 44, $true, $k),
    @("Train No./Name: 12727 / GODAVARI EXPRESS", 60, 210, 32, $false, $k),
    @("From: RAJAHMUNDRY (RJY)      To: SECUNDERABAD JN (SC)", 60, 270, 32, $false, $k),
    @("Date of Journey: 15-Oct-2026     Departure: 18:45", 60, 330, 32, $false, $k),
    @("Class: THIRD AC (3A)     Quota: GENERAL", 60, 390, 32, $false, $k),
    @("Passenger: TEST PASSENGER   Age: 25   Status: CNF/B2/34", 60, 470, 32, $false, $k),
    @("Coach/Seat: B2 / 34", 60, 530, 32, $true, $k),
    @("Total Fare: Rs. 1085.00", 60, 610, 34, $true, $k)
)

New-Doc "test_boarding_pass.jpg" 1400 560 "#ffffff" @(
    @("KAIRO AIRWAYS", 40, 30, 44, $true, "#5a2d82"),
    @("BOARDING PASS", 980, 36, 36, $true, $k),
    @("Passenger: TEST/TRAVELLER MR", 40, 120, 32, $false, $k),
    @("Flight: KA 2345", 40, 180, 34, $true, $k),
    @("From: HYDERABAD (HYD)   To: BENGALURU (BLR)", 40, 240, 32, $false, $k),
    @("Date: 18 Oct 2026   Boarding: 07:10   Departure: 07:40", 40, 300, 32, $false, $k),
    @("Gate: 22     Seat: 14C     Zone: 2", 40, 360, 34, $true, $k),
    @("PNR: KX7Q2M", 40, 430, 34, $true, $k)
)
