$ErrorActionPreference = 'Stop'
$src = 'D:\border&watermark\_research\docs\FrameMark-delivery.docx'
$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
try {
  $doc = $word.Documents.Open($src, $false, $true)
  Write-Output ("Pages: " + $doc.ComputeStatistics(2))
  Write-Output ("Words: " + $doc.ComputeStatistics(0))
  Write-Output ("InlineShapes: " + $doc.InlineShapes.Count)
  Write-Output ("Tables: " + $doc.Tables.Count)
  $doc.Close($false)
} finally { $word.Quit() }
