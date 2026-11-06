param([string]$ListFile, [string]$OutDir)
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null = [Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media.SpeechSynthesis, ContentType = WindowsRuntime]
$null = [Windows.Storage.Streams.DataReader, Windows.Storage.Streams, ContentType = WindowsRuntime]
$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
function Await($op, [Type]$t) { $task = $asTask.MakeGenericMethod($t).Invoke($null, @($op)); $task.Wait() | Out-Null; $task.Result }
$synth = New-Object Windows.Media.SpeechSynthesis.SpeechSynthesizer
$voices = [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices | Where-Object { $_.Language -eq 'ru-RU' }
$lines = Get-Content -Encoding UTF8 $ListFile
$i = 0
foreach ($line in $lines) {
  $parts = $line.Split("`t"); $v = $parts[0]; $text = $parts[1]
  $synth.Voice = ($voices | Where-Object { $_.DisplayName -like "*$v*" })[0]
  $stream = Await ($synth.SynthesizeTextToStreamAsync($text)) ([Windows.Media.SpeechSynthesis.SpeechSynthesisStream])
  $reader = New-Object Windows.Storage.Streams.DataReader($stream.GetInputStreamAt(0))
  $n = [uint32]$stream.Size
  $null = Await ($reader.LoadAsync($n)) ([uint32])
  $buf = New-Object byte[] $n; $reader.ReadBytes($buf)
  [System.IO.File]::WriteAllBytes((Join-Path $OutDir ("{0:D3}_{1}.wav" -f $i, $v)), $buf)
  $i++
}
Write-Output "ok $i"
