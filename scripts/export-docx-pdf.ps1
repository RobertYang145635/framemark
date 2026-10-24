$ErrorActionPreference = 'Stop'
$src = 'D:\border&watermark\_research\docs\FrameMark-delivery.docx'
$dst = 'D:\border&watermark\_research\docs\FrameMark-delivery.pdf'
$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
try {
  $doc = $word.Documents.Open($src, $false, $true)
  $doc.SaveAs([ref]$dst, [ref]17)
  $doc.Close($false)
  Write-Output "PDF OK: $dst"
} finally {
  $word.Quit()
}
