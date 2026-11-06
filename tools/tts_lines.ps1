# Синтез реплик голосами Windows (WinRT OneCore) для синтетических тестов.
# Вход: TSV  speaker<TAB>voice<TAB>pitch<TAB>rate<TAB>text   (pitch: "+0%", "-15%"; rate: 1.0)
# Выход: OutDir/<speaker>/<NNN>.wav + .txt  — формат --layout dirs для tools/make_synthetic.py
# Запуск: powershell -ExecutionPolicy Bypass -File tools\tts_lines.ps1 -ListFile lines.tsv -OutDir .cache\tts\src
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
  if ($line.Trim() -eq '') { continue }
  $p = $line.Split("`t")
  $spk = $p[0]; $voice = ($voices | Where-Object { $_.DisplayName -like "*$($p[1])*" })[0]
  $text = $p[4]
  $synth.Voice = $voice
  $esc = [System.Security.SecurityElement]::Escape($text)
  $ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='ru-RU'><voice name='$($voice.DisplayName)'><prosody pitch='$($p[2])' rate='$($p[3])'>$esc</prosody></voice></speak>"
  $stream = Await ($synth.SynthesizeSsmlToStreamAsync($ssml)) ([Windows.Media.SpeechSynthesis.SpeechSynthesisStream])
  $reader = New-Object Windows.Storage.Streams.DataReader($stream.GetInputStreamAt(0))
  $n = [uint32]$stream.Size
  $null = Await ($reader.LoadAsync($n)) ([uint32])
  $buf = New-Object byte[] $n; $reader.ReadBytes($buf)
  $dir = Join-Path $OutDir $spk; New-Item -ItemType Directory -Force $dir | Out-Null
  $base = Join-Path $dir ("{0:D3}" -f $i)
  [System.IO.File]::WriteAllBytes("$base.wav", $buf)
  [System.IO.File]::WriteAllText("$base.txt", $text, (New-Object System.Text.UTF8Encoding($false)))
  $i++
}
Write-Output "ok $i"
