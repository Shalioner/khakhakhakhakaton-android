#!/usr/bin/env bash
# Прогон «Нейро-стенографиста» на физическом телефоне: установка APK, заливка тестовых аудио,
# запуск BenchActivity, сбор памяти/температуры, adb pull, report.py и сводка.
# Windows (Git Bash) и Linux/macOS. План и критерии: docs/PHONE_TEST_PLAN.md.
#
#   tools/phone_bench.sh [опции] [ПАПКА_С_АУДИО]
#
# ПАПКА_С_АУДИО по умолчанию testdata/test (+ «злые» файлы .cache/edge + варианты форматов).
# Если папка указана явно — берётся только она (добавить --edge / --fmt вручную).
#
# Режимы:
#   (по умолчанию)     автоматический прогон BenchActivity
#   --manual-push      положить 3 «жюри»-файла (+ mp3/opus, тишину, битый) в /sdcard/Download/NeuroSteno
#                      для ручного импорта через SAF (tools/phone_airplane_check.md) и выйти
#   --manual-collect   забрать экспортированные из UI *.json из /sdcard/Download, проверить контракт и WER
#
# Опции:
#   --dry-run          только печатать команды adb/python (локальная подготовка .cache/phone_stage выполняется)
#   --serial S         adb -s S (если подключено несколько устройств)
#   --apk PATH         APK (по умолчанию src/android/app/build/outputs/apk/release/app-release.apk)
#   --skip-install     не ставить APK (уже стоит)
#   --clean-install    adb uninstall перед установкой (стирает данные приложения на телефоне)
#   --jury             только 3 файла сценария «как жюри» (быстрый прогон ~7 мин аудио)
#   --edge / --no-edge «злые» файлы (тишина/шум/музыка/«да»/стерео 48k/8 бит/битый)
#   --fmt / --no-fmt   варианты форматов одного файла (mp3/m4a/ogg/opus/amr/flac)
#   --long MIN         добавить склеенный длинный файл на MIN минут (риск OOM диаризации)
#   --exclude REGEX    исключить файлы (по имени без расширения)
#   --interval N       период dumpsys meminfo, с (по умолчанию 3)
#   --thermal N        период температуры/частот, с (по умолчанию 15; 0 = не собирать)
#   --timeout MIN      общий таймаут ожидания (по умолчанию из длительности аудио)
#   --stall MIN        нет новой строки bench дольше MIN → зависание (по умолчанию из длины файлов)
#   --retries N        сколько раз перезапускать после крэша, пропуская упавший файл (по умолчанию 2)
#   --out DIR          куда складывать (по умолчанию out/phone/<дата_время>)
#   --keep-remote      не очищать bench-папку на телефоне перед заливкой
#   --no-stayon        не включать «не гасить экран при USB» (svc power stayon usb) на время прогона
#   -h | --help
#
# Переменные: ADB (путь к adb), PYTHON (по умолчанию python), FFMPEG (путь к ffmpeg).
set -u -o pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'   # Git Bash: не превращать /sdcard/... в C:/Program Files/Git/sdcard/...
[ -z "${LC_ALL:-}${LANG:-}" ] && export LANG=C.UTF-8  # кириллица в printf %q / awk
export PYTHONUTF8=1 PYTHONIOENCODING=utf-8

PKG=ru.neurosteno.app
REMOTE=/sdcard/Android/data/$PKG/files/bench
MANUAL_REMOTE=/sdcard/Download/NeuroSteno
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT" || exit 2

# ------------------------------------------------------------------ аргументы
DRY=0; SERIAL=""; APK=src/android/app/build/outputs/apk/release/app-release.apk
SKIP_INSTALL=0; CLEAN_INSTALL=0; JURY=0; EDGE=""; FMT=""; LONG=0; EXCLUDE=""
INTERVAL=3; THERMAL=15; TIMEOUT_MIN=""; STALL_MIN=""; RETRIES=2; OUT=""; KEEP_REMOTE=0; STAYON=1
MODE=bench; SRC=""
usage() { sed -n '2,/^set -u/p' "$0" | sed -e '$d' -e 's/^# \{0,1\}//'; }
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --serial) SERIAL=$2; shift ;;
    --apk) APK=$2; shift ;;
    --skip-install) SKIP_INSTALL=1 ;;
    --clean-install) CLEAN_INSTALL=1 ;;
    --jury) JURY=1 ;;
    --edge) EDGE=1 ;; --no-edge) EDGE=0 ;;
    --fmt) FMT=1 ;; --no-fmt) FMT=0 ;;
    --long) LONG=$2; shift ;;
    --exclude) EXCLUDE=$2; shift ;;
    --interval) INTERVAL=$2; shift ;;
    --thermal) THERMAL=$2; shift ;;
    --timeout) TIMEOUT_MIN=$2; shift ;;
    --stall) STALL_MIN=$2; shift ;;
    --retries) RETRIES=$2; shift ;;
    --out) OUT=$2; shift ;;
    --keep-remote) KEEP_REMOTE=1 ;;
    --no-stayon) STAYON=0 ;;
    --manual-push) MODE=manual-push ;;
    --manual-collect) MODE=manual-collect ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "неизвестная опция: $1 (см. --help)" >&2; exit 2 ;;
    *) SRC=$1 ;;
  esac
  shift
done
if [ -z "$SRC" ]; then
  SRC=testdata/test; DEFAULT_SRC=1
  [ -z "$EDGE" ] && EDGE=1; [ -z "$FMT" ] && FMT=1
else
  DEFAULT_SRC=0; [ -z "$EDGE" ] && EDGE=0; [ -z "$FMT" ] && FMT=0
fi
[ "$JURY" = 1 ] && { [ "$DEFAULT_SRC" = 1 ] && { EDGE=0; FMT=0; }; }

# ------------------------------------------------------------------ утилиты
c_red=$'\e[31m'; c_grn=$'\e[32m'; c_yel=$'\e[33m'; c_off=$'\e[0m'
[ -t 1 ] || { c_red=""; c_grn=""; c_yel=""; c_off=""; }
say()  { printf '%s[phone]%s %s\n' "$c_grn" "$c_off" "$*"; }
warn() { printf '%s[phone] ВНИМАНИЕ:%s %s\n' "$c_yel" "$c_off" "$*" >&2; }
die()  { printf '%s[phone] ОШИБКА:%s %s\n' "$c_red" "$c_off" "$*" >&2; exit 1; }
# путь для нативных Windows-программ (adb.exe, python.exe): /c/x → C:/x
nat() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
show() { { printf '+'; printf ' %q' "$@"; printf '\n'; } >&2; }   # в stderr: видно и при перенаправлении stdout

find_adb() {
  if [ -n "${ADB:-}" ]; then printf '%s' "$ADB"; return; fi
  if command -v adb >/dev/null 2>&1; then command -v adb; return; fi
  local c
  for c in "C:/Users/leo/Android/Sdk/platform-tools/adb.exe" \
           "${ANDROID_HOME:-/nonexistent}/platform-tools/adb" "${ANDROID_HOME:-/nonexistent}/platform-tools/adb.exe" \
           "${ANDROID_SDK_ROOT:-/nonexistent}/platform-tools/adb.exe" "$HOME/Android/Sdk/platform-tools/adb" \
           "${LOCALAPPDATA:-/nonexistent}/Android/Sdk/platform-tools/adb.exe"; do
    [ -x "$c" ] || [ -f "$c" ] && { printf '%s' "$c"; return; }
  done
  printf 'adb'
}
ADB=$(find_adb)
PY=${PYTHON:-python}
adb_() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }
# x: команда с побочным эффектом (в --dry-run только печать)
x() { if [ "$DRY" = 1 ]; then show "$@"; return 0; fi; "$@"; }
# xa: то же для adb
xa() { if [ "$DRY" = 1 ]; then show adb ${SERIAL:+-s "$SERIAL"} "$@"; return 0; fi; adb_ "$@"; }
# sh_: запрос adb shell (в --dry-run печать, пустой вывод)
sh_() { if [ "$DRY" = 1 ]; then show adb ${SERIAL:+-s "$SERIAL"} shell "$@"; return 0; fi; adb_ shell "$@" 2>/dev/null | tr -d '\r'; }
now() { date +%s; }

# ------------------------------------------------------------------ устройство
check_device() {
  say "adb: $ADB"
  if [ "$DRY" = 1 ]; then show adb devices -l; return 0; fi
  "$ADB" version >/dev/null 2>&1 || die "adb не запускается ($ADB). Укажите ADB=/путь/к/adb"
  local devs; devs=$("$ADB" devices -l | tr -d '\r' | sed 1d | grep -v '^$' || true)
  echo "$devs" | sed 's/^/    /'
  if echo "$devs" | grep -q unauthorized; then die "телефон unauthorized: разблокируйте и подтвердите «Разрешить отладку по USB»"; fi
  local n; n=$(echo "$devs" | grep -cE '[[:space:]]device([[:space:]]|$)' || true)
  [ "$n" -ge 1 ] || die "нет устройств: включите «Отладка по USB», кабель с данными, режим USB «Передача файлов»"
  if [ "$n" -gt 1 ] && [ -z "$SERIAL" ]; then die "подключено $n устройств — укажите --serial"; fi
}

device_info() {  # → $RUN/device.txt (key=value) и печать
  local f=$1
  local q='echo "model=$(getprop ro.product.manufacturer) $(getprop ro.product.model) ($(getprop ro.product.device))";
echo "android=$(getprop ro.build.version.release)"; echo "sdk=$(getprop ro.build.version.sdk)";
echo "build=$(getprop ro.build.fingerprint)";
echo "soc=$(getprop ro.soc.manufacturer) $(getprop ro.soc.model) / $(getprop ro.board.platform) / $(getprop ro.hardware)";
echo "abi=$(getprop ro.product.cpu.abilist)";
echo "heapgrowthlimit=$(getprop dalvik.vm.heapgrowthlimit)"; echo "heapsize=$(getprop dalvik.vm.heapsize)";
echo "low_ram=$(getprop ro.config.low_ram)";
echo "cpus=$(nproc 2>/dev/null) max_khz=$(cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq 2>/dev/null | sort -u | tr "\n" " ")";
grep -E "^(MemTotal|MemAvailable|SwapTotal|SwapFree):" /proc/meminfo | sed "s/: */=/";
echo "data_df=$(df -k /data 2>/dev/null | tail -n 1)";
echo "airplane=$(settings get global airplane_mode_on)";
echo "stay_on=$(settings get global stay_on_while_plugged_in)";
echo "battery=$(dumpsys battery | grep -E "^  (level|temperature|status|AC powered|USB powered):" | tr -s " " | tr "\n" ";")";
echo "keyguard=$(dumpsys window 2>/dev/null | grep -m1 -oE "(mDreamingLockscreen|isKeyguardShowing|mShowingLockscreen)=(true|false)")";
echo "pkg_version=$(dumpsys package ru.neurosteno.app | grep -m1 -E "versionName=" | tr -d " ")";
echo "pkg_updated=$(dumpsys package ru.neurosteno.app | grep -m1 -E "lastUpdateTime=" | tr -d " ")"'
  local nl=$'\n'
  local raw; raw=$(sh_ "${q//$nl/ }")   # одной строкой: adb shell надёжнее без переводов строк
  [ "$DRY" = 1 ] && return 0
  local memkb; memkb=$(echo "$raw" | sed -n 's/^MemTotal=\([0-9]*\).*/\1/p')
  {
    echo "$raw"
    [ -n "$memkb" ] && echo "ram_total=$(awk -v k="$memkb" 'BEGIN{printf "%.2f GB", k/1048576}')"
    echo "host=$(uname -a)"
    echo "collected=$(date '+%Y-%m-%d %H:%M:%S')"
  } > "$f"
  sed 's/^/    /' "$f" | grep -vE '^    (build|host|collected)='
  echo "$raw" | grep -q '^abi=.*arm64-v8a' || warn "ABI телефона не arm64-v8a — APK (arm64-v8a) не встанет"
  local freekb; freekb=$(echo "$raw" | sed -n 's/^data_df=//p' | awk 'NF >= 6 && $4 ~ /^[0-9]+$/ { print $4; exit }')  # df -k: FS 1K-blocks Used Available Use% Mount
  [ -n "$freekb" ] && [ "$freekb" -lt 3500000 ] && warn "на /data свободно $((freekb / 1024)) МБ: нужно ≥ ~3.5 ГБ (APK 1 ГБ + распаковка + копия LLM 0.5 ГБ)"
  echo "$raw" | grep -qE 'keyguard=.*=true' && warn "экран заблокирован — разблокируйте телефон (BenchActivity должна быть на переднем плане)"
  echo "$raw" | grep -q '^low_ram=true' && warn "ro.config.low_ram=true — Go-устройство, риск OOM"
  return 0
}

install_apk() {
  [ "$SKIP_INSTALL" = 1 ] && { say "установка пропущена (--skip-install)"; return 0; }
  [ -f "$APK" ] || die "нет APK: $APK (соберите: cd src/android && ./gradlew :app:assembleRelease)"
  local sz; sz=$(stat -c %s "$APK" 2>/dev/null || wc -c < "$APK")
  say "APK $(nat "$APK") — $((sz / 1048576)) МБ; установка займёт 1–4 мин (на Xiaomi/Huawei подтвердите установку на экране)"
  if [ "$CLEAN_INSTALL" = 1 ]; then xa uninstall "$PKG" || true; fi
  local t0; t0=$(now)
  if ! xa install -r "$(nat "$APK")"; then
    warn "потоковая установка не прошла — пробую push в /data/local/tmp + pm install"
    xa push "$(nat "$APK")" /data/local/tmp/neurosteno.apk || die "adb push APK не прошёл (место на телефоне?)"
    xa shell pm install -r /data/local/tmp/neurosteno.apk || { xa shell rm -f /data/local/tmp/neurosteno.apk; die "pm install не прошёл: INSUFFICIENT_STORAGE → освободить ≥3.5 ГБ; NO_MATCHING_ABIS → не arm64"; }
    xa shell rm -f /data/local/tmp/neurosteno.apk
  fi
  [ "$DRY" = 1 ] || say "установлено за $(( $(now) - t0 )) с"
  # правило 1: в манифесте нет INTERNET
  local inet; inet=$(sh_ "dumpsys package $PKG | grep -c 'android.permission.INTERNET'")
  if [ "$DRY" != 1 ]; then
    if [ "${inet:-0}" != 0 ]; then warn "в установленном пакете есть android.permission.INTERNET — нарушение правила 1!"; else say "INTERNET в пакете нет (правило 1) ✓"; fi
  fi
}

# ------------------------------------------------------------------ фоновые сборщики
mem_sampler() {  # epoch,pid,pss_kb,rss_kb,native_pss_kb,java_pss_kb,vmrss_kb,vmhwm_kb
  local csv=$RUN/meminfo.csv raw=$RUN/meminfo_raw.txt ts out
  echo "epoch,pid,pss_kb,rss_kb,native_pss_kb,java_pss_kb,vmrss_kb,vmhwm_kb" > "$csv"
  while [ ! -f "$STOPFILE" ]; do
    ts=$(now)
    out=$(adb_ shell "pid=\$(pidof $PKG); echo PID=\$pid; if [ -n \"\$pid\" ]; then grep -E '^Vm(RSS|HWM):' /proc/\$pid/status 2>/dev/null; dumpsys meminfo $PKG; fi" 2>/dev/null | tr -d '\r')
    printf '=== %s\n%s\n' "$ts" "$out" >> "$raw"
    printf '%s\n' "$out" | awk -v ts="$ts" '
      /^PID=/ { pid = substr($0, 5); sub(/ .*/, "", pid) }
      /^VmRSS:/ { vmrss = $2 }  /^VmHWM:/ { vmhwm = $2 }
      /TOTAL PSS:/ { for (i = 2; i <= NF; i++) { if ($i == "PSS:" && $(i-1) == "TOTAL") pss = $(i+1); if ($i == "RSS:" && $(i-1) == "TOTAL") rss = $(i+1) } }
      /^ *TOTAL: +[0-9]/ { if (pss == "") pss = $2 }
      /^ *TOTAL +[0-9]/ { if (tpss == "") tpss = $2 }
      /^ *Native Heap: +[0-9]/ { nat = $3 }  /^ *Java Heap: +[0-9]/ { jav = $3 }
      END { if (pss == "") pss = tpss; if (pid != "") print ts "," pid "," pss "," rss "," nat "," jav "," vmrss "," vmhwm }' >> "$csv"
    sleep "$INTERVAL"
  done
}

thermal_sampler() {  # epoch,battery_c,thermal_status,cpu_max_c,skin_c,cpu_mhz
  local csv=$RUN/thermal.csv ts out
  echo "epoch,battery_c,thermal_status,cpu_max_c,skin_c,cpu_mhz" > "$csv"
  while [ ! -f "$STOPFILE" ]; do
    ts=$(now)
    out=$(adb_ shell "dumpsys battery | grep -m1 ' temperature:'; dumpsys thermalservice 2>/dev/null | grep -E 'Thermal Status|mType=(0|3),'; echo FREQ=\$(cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null)" 2>/dev/null | tr -d '\r')
    printf '%s\n' "$out" | awk -v ts="$ts" '
      / temperature:/ { bt = $2 / 10 }
      /Thermal Status:/ { st = $NF }
      /mType=0,|mType=3,/ { if (match($0, /mValue=-?[0-9.]+/)) { v = substr($0, RSTART + 7, RLENGTH - 7) + 0
                            if ($0 ~ /mType=0,/) { if (cpu == "" || v > cpu) cpu = v } else { if (skin == "" || v > skin) skin = v } } }
      /^FREQ=/ { s = substr($0, 6); n = split(s, a, " "); f = ""; for (i = 1; i <= n; i++) f = f (i > 1 ? ";" : "") int(a[i] / 1000) }
      END { print ts "," bt "," st "," cpu "," skin "," f }' >> "$csv"
    sleep "$THERMAL"
  done
}

STAYON_ORIG=""
LOGCAT_PID=""; MEM_PID=""; THERM_PID=""; STOPFILE=""
# kill с потомками (Git Bash: ps без -o; дети adb/tail иначе остаются висеть)
kill_tree() {
  local p=$1 c
  [ -n "$p" ] || return 0
  for c in $( (ps -A -o pid=,ppid= 2>/dev/null || ps | awk 'NR > 1 { print $1, $2 }') | awk -v p="$p" '$2 == p { print $1 }'); do
    kill_tree "$c"
  done
  kill "$p" 2>/dev/null
  return 0
}
cleanup() {
  [ -n "$STOPFILE" ] && touch "$STOPFILE" 2>/dev/null
  for p in $MEM_PID $THERM_PID $LOGCAT_PID; do kill_tree "$p"; done
  MEM_PID=""; THERM_PID=""; LOGCAT_PID=""
  if [ -n "$STAYON_ORIG" ] && [ "$DRY" != 1 ]; then
    adb_ shell settings put global stay_on_while_plugged_in "$STAYON_ORIG" >/dev/null 2>&1 && say "stay_on_while_plugged_in восстановлен ($STAYON_ORIG)"
    STAYON_ORIG=""
  fi
}
trap cleanup EXIT
INTERRUPTED=0
on_int() { warn "прервано"; exit 130; }
trap on_int INT TERM

start_logcat() {
  if [ "$DRY" = 1 ]; then show adb logcat -v threadtime -b main,system,crash ">>$RUN/logcat.txt" "&"; return; fi
  # exec: $! — сам процесс adb, а не subshell (иначе kill оставляет висеть adb logcat)
  if [ -n "$SERIAL" ]; then
    ( exec "$ADB" -s "$SERIAL" logcat -v threadtime -b main,system,crash >> "$RUN/logcat.txt" 2>&1 ) &
  else
    ( exec "$ADB" logcat -v threadtime -b main,system,crash >> "$RUN/logcat.txt" 2>&1 ) &
  fi
  LOGCAT_PID=$!
}

keep_awake() {
  if [ "$STAYON" = 1 ]; then
    if [ "$DRY" = 1 ]; then show adb shell svc power stayon usb; else
      STAYON_ORIG=$(sh_ settings get global stay_on_while_plugged_in); STAYON_ORIG=${STAYON_ORIG:-0}
      adb_ shell svc power stayon usb >/dev/null 2>&1 || warn "svc power stayon не сработал — включите «Не выключать экран» в настройках разработчика"
    fi
  fi
  xa shell input keyevent KEYCODE_WAKEUP >/dev/null
  xa shell wm dismiss-keyguard >/dev/null
}

launch_bench() {
  xa shell am force-stop "$PKG"
  [ "$DRY" = 1 ] || echo "$(now),START," >> "$RUN/events.csv"
  if [ "$DRY" = 1 ]; then xa shell am start -W -n "$PKG/.BenchActivity"; return 0; fi
  adb_ shell am start -W -n "$PKG/.BenchActivity" </dev/null | tr -d '\r' | grep -E 'Status|TotalTime|Error' | sed 's/^/    /'
}

# ------------------------------------------------------------------ режим bench
mode_bench() {
  local stamp; stamp=$(date +%Y%m%d_%H%M%S)
  RUN=${OUT:-out/phone/$stamp}
  [ "$DRY" = 1 ] && [ -z "$OUT" ] && RUN=.cache/phone_dryrun/$stamp   # dry-run не мусорит в out/
  check_device   # до mkdir: без телефона не оставляем пустых папок
  mkdir -p "$RUN"
  STOPFILE=$RUN/.stop; rm -f "$STOPFILE"
  say "результаты → $RUN"
  device_info "$RUN/device.txt"
  install_apk

  # 1. локальная подготовка набора (выполняется и в --dry-run: только .cache/)
  local STAGE=.cache/phone_stage
  local pargs=(--stage "$STAGE" --src "$(nat "$SRC")")
  [ "$EDGE" = 1 ] && pargs+=(--edge)
  [ "$FMT" = 1 ] && pargs+=(--fmt)
  [ "$JURY" = 1 ] && pargs+=(--jury)
  [ "$LONG" != 0 ] && pargs+=(--long "$LONG")
  [ -n "$EXCLUDE" ] && pargs+=(--exclude "$EXCLUDE")
  show "$PY" tools/phone_prepare.py "${pargs[@]}"
  "$PY" tools/phone_prepare.py "${pargs[@]}" || die "phone_prepare.py не отработал"
  cp "$STAGE/manifest.json" "$RUN/"
  local d; for d in refs_main refs_fmt refs_long; do rm -rf "${RUN:?}/$d"; cp -r "$STAGE/$d" "$RUN/$d"; done
  local files=("$STAGE"/push/*)
  [ -e "${files[0]}" ] || die "нечего заливать"
  local est; est=$("$PY" -c "import json,sys;m=json.load(open(sys.argv[1],encoding='utf-8'));d=[v.get('duration_ms') or 120000 for v in m.values()];print(sum(d)//60000+1, max(d)//60000+1)" "$STAGE/manifest.json")
  local audio_min=${est% *} longest_min=${est#* }
  local timeout_min=${TIMEOUT_MIN:-$(( audio_min * 3 / 2 + 15 ))}
  local stall_min=${STALL_MIN:-$(( longest_min * 2 > 15 ? longest_min * 2 : 15 ))}
  say "аудио ≈ $audio_min мин (самый длинный ≈ $longest_min мин); таймаут $timeout_min мин, зависание после $stall_min мин без прогресса"

  # 2. заливка на телефон
  xa shell am force-stop "$PKG"
  if [ "$KEEP_REMOTE" != 1 ]; then
    xa shell "rm -rf $REMOTE/out $REMOTE/done $REMOTE/crashed; rm -f $REMOTE/*"
  fi
  xa shell mkdir -p "$REMOTE"
  if [ "$DRY" != 1 ] && [ -z "$(sh_ "ls -d $REMOTE 2>/dev/null")" ]; then
    warn "shell не создал $REMOTE — создаю запуском BenchActivity с пустой папкой"
    adb_ shell am start -W -n "$PKG/.BenchActivity" >/dev/null 2>&1; sleep 5; adb_ shell am force-stop "$PKG"
    [ -n "$(sh_ "ls -d $REMOTE 2>/dev/null")" ] || die "нет $REMOTE на телефоне"
    adb_ shell "rm -rf $REMOTE/out"
  fi
  local natfiles=() f; for f in "${files[@]}"; do natfiles+=("$(nat "$f")"); done
  say "заливаю ${#files[@]} файлов ($(du -sh "$STAGE/push" | cut -f1)) в $REMOTE"
  xa push "${natfiles[@]}" "$REMOTE/" | tail -n 1
  if [ "$DRY" != 1 ]; then
    local cnt; cnt=$(sh_ "ls $REMOTE | grep -c '\\.'")
    [ "${cnt:-0}" -ge "${#files[@]}" ] || die "на телефоне $cnt файлов вместо ${#files[@]}"
  fi

  # 3. запуск + сборщики
  : > "$RUN/events.csv"; [ "$DRY" = 1 ] || echo "epoch,event,name" > "$RUN/events.csv"
  xa logcat -c
  start_logcat
  keep_awake
  launch_bench
  if [ "$DRY" = 1 ]; then
    show "(фон каждые ${INTERVAL}с)" adb shell "pid=\$(pidof $PKG); grep -E '^Vm(RSS|HWM):' /proc/\$pid/status; dumpsys meminfo $PKG" "→ $RUN/meminfo.csv"
    [ "$THERMAL" != 0 ] && show "(фон каждые ${THERMAL}с)" adb shell "dumpsys battery; dumpsys thermalservice; cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq" "→ $RUN/thermal.csv"
    show "(ожидание)" grep "bench done" "$RUN/logcat.txt"
  else
    mem_sampler & MEM_PID=$!
    [ "$THERMAL" != 0 ] && { thermal_sampler & THERM_PID=$!; }
    wait_bench "$timeout_min" "$stall_min"
  fi

  # 4. сбор
  touch "$STOPFILE"
  sleep_quiet 1
  cleanup
  xa pull "$REMOTE/out" "$(nat "$RUN")/" | tail -n 1
  sh_ "dumpsys meminfo $PKG" > "$RUN/meminfo_final.txt"
  if grep -qE 'FATAL EXCEPTION|ANR in '"$PKG"'|Fatal signal' "$RUN/logcat.txt" 2>/dev/null; then
    sh_ "dumpsys dropbox --print data_app_crash" > "$RUN/dropbox_crash.txt"
    sh_ "dumpsys dropbox --print data_app_anr" > "$RUN/dropbox_anr.txt"
    sh_ "dumpsys dropbox --print data_app_native_crash" > "$RUN/dropbox_native.txt"
  fi

  # 5. отчёты
  report_all
}

sleep_quiet() { [ "$DRY" = 1 ] || sleep "$1"; }

wait_bench() {
  local timeout_min=$1 stall_min=$2
  local t_start t_last seen=0 dead=0 retries=0 tick=0 n line name rtf
  t_start=$(now); t_last=$t_start
  local crashed=" "
  say "жду «bench done» (Ctrl+C — прервать ожидание; собранное всё равно будет скачано)"
  INTERRUPTED=0
  trap 'INTERRUPTED=1' INT
  while :; do
    sleep 2; tick=$((tick + 1))
    if [ "$INTERRUPTED" = 1 ]; then
      echo "$(now),INTERRUPT," >> "$RUN/events.csv"; warn "ожидание прервано"; trap on_int INT; return 1
    fi
    mapfile -t lines < <(grep -o 'NeuroSteno *: bench "[^"]*":.*' "$RUN/logcat.txt" 2>/dev/null | tr -d '\r')
    n=${#lines[@]}
    while [ "$seen" -lt "$n" ]; do
      line=${lines[$seen]}
      name=$(printf '%s' "$line" | sed -n 's/.*bench "\([^"]*\)":.*/\1/p')
      rtf=$(printf '%s' "$line" | sed -n 's/.*"rtf":\([0-9.]*\).*/\1/p')
      if printf '%s' "$line" | grep -q '"error"'; then
        printf '    %s%-40s ERROR%s %s\n' "$c_yel" "$name" "$c_off" "$(printf '%s' "$line" | sed -n 's/.*"error":\(.*\)}.*/\1/p' | cut -c1-100)"
      else
        printf '    %-40s RTF %s  (%s)\n' "$name" "${rtf:-?}" "$(printf '%s' "$line" | grep -oE '"(total_ms|native_heap_mb)":[0-9]+' | tr '\n' ' ')"
      fi
      echo "$(now),FILE,$name" >> "$RUN/events.csv"
      seen=$((seen + 1)); t_last=$(now)
    done
    if grep -q 'bench done' "$RUN/logcat.txt" 2>/dev/null; then
      echo "$(now),DONE," >> "$RUN/events.csv"; say "bench done за $(( $(now) - t_start )) с"; trap on_int INT; return 0
    fi
    if [ -n "$LOGCAT_PID" ] && ! kill -0 "$LOGCAT_PID" 2>/dev/null; then
      warn "logcat отвалился (USB?) — перезапускаю"; adb_ wait-for-device; start_logcat
    fi
    if [ $((tick % 5)) = 0 ]; then
      if [ "$(adb_ get-state 2>/dev/null | tr -d '\r')" != device ]; then
        warn "телефон не на связи (USB?) — жду"; dead=0; continue
      fi
      if [ -z "$(sh_ "pidof $PKG")" ]; then dead=$((dead + 1)); else dead=0; fi
      if [ "$dead" -ge 2 ]; then
        local cur; cur=$(next_unprocessed "$crashed")
        echo "$(now),CRASH,$cur" >> "$RUN/events.csv"
        warn "процесс $PKG умер, вероятно на файле «$cur» (см. logcat.txt: FATAL/lowmemorykiller)"
        if [ "$retries" -ge "$RETRIES" ] || [ -z "$cur" ]; then warn "лимит перезапусков исчерпан"; return 1; fi
        retries=$((retries + 1)); crashed="$crashed$cur "
        move_processed "$cur"
        say "перезапуск BenchActivity без «$cur» (попытка $retries/$RETRIES)"
        launch_bench; dead=0; t_last=$(now)
      fi
    fi
    if [ $(( $(now) - t_last )) -gt $((stall_min * 60)) ]; then
      echo "$(now),STALL," >> "$RUN/events.csv"
      warn "нет прогресса $stall_min мин — зависание? Сохраняю, что есть"; return 1
    fi
    if [ $(( $(now) - t_start )) -gt $((timeout_min * 60)) ]; then
      echo "$(now),TIMEOUT," >> "$RUN/events.csv"; warn "общий таймаут $timeout_min мин"; return 1
    fi
  done
}

# Первый (в порядке BenchActivity: сортировка File = по имени, C-локаль) файл без строки bench.
next_unprocessed() {
  local skip=$1 f b
  while read -r f; do
    b=${f%.*}
    case "$skip" in *" $b "*) continue ;; esac
    grep -q "bench \"$b\":" "$RUN/logcat.txt" 2>/dev/null || { printf '%s' "$b"; return; }
  done < <(ls .cache/phone_stage/push | LC_ALL=C sort)
}

# Перед перезапуском: обработанные → bench/done, упавший → bench/crashed (BenchActivity берёт только файлы верхнего уровня).
move_processed() {
  local cur=$1 f b cmd="mkdir -p $REMOTE/done $REMOTE/crashed"
  while read -r f; do
    b=${f%.*}
    if [ "$b" = "$cur" ]; then cmd="$cmd; mv '$REMOTE/$f' $REMOTE/crashed/"
    elif grep -q "bench \"$b\":" "$RUN/logcat.txt" 2>/dev/null; then cmd="$cmd; mv '$REMOTE/$f' $REMOTE/done/ 2>/dev/null"; fi
  done < <(ls .cache/phone_stage/push)
  adb_ shell "$cmd"
}

report_all() {
  local rc=0
  x "$PY" tools/phone_summary.py "$(nat "$RUN")" --write-timings
  local T; T=$(nat "$RUN/timings_report.json")
  local refs=$RUN/refs_main
  # полный набор testdata/test без фильтров → эталоны прямо из testdata/test (как в ТЗ)
  if [ "$DEFAULT_SRC" = 1 ] && [ "$JURY" != 1 ] && [ -z "$EXCLUDE" ]; then refs=testdata/test; fi
  if [ "$DRY" = 1 ] || ls "$refs"/*.json >/dev/null 2>&1; then
    say "=== report.py: основной набор ($refs) ==="
    x "$PY" tools/report.py "$(nat "$refs")" "$(nat "$RUN/out")" --timings "$T" --csv "$(nat "$RUN/report_main.csv")" --md "$(nat "$RUN/report_main.md")" || rc=1
  fi
  if [ "$DRY" = 1 ] || ls "$RUN/refs_fmt"/*.json >/dev/null 2>&1; then
    say "=== report.py: форматы ==="
    x "$PY" tools/report.py "$(nat "$RUN/refs_fmt")" "$(nat "$RUN/out")" --timings "$T" --csv "$(nat "$RUN/report_fmt.csv")" --md "$(nat "$RUN/report_fmt.md")" || rc=1
  fi
  if [ "$DRY" = 1 ] || ls "$RUN/refs_long"/*.json >/dev/null 2>&1; then
    say "=== report.py: длинный файл ==="
    x "$PY" tools/report.py "$(nat "$RUN/refs_long")" "$(nat "$RUN/out")" --timings "$T" --csv "$(nat "$RUN/report_long.csv")" --md "$(nat "$RUN/report_long.md")" || rc=1
  fi
  say "=== сводка ==="
  x "$PY" tools/phone_summary.py "$(nat "$RUN")" || rc=1
  say "всё в $RUN (logcat.txt, meminfo.csv, thermal.csv, report_*.md, summary.md)"
  return $rc
}

# ------------------------------------------------------------------ ручные режимы
mode_manual_push() {
  check_device
  local STAGE=.cache/phone_stage_manual
  local pargs=(--stage "$STAGE" --src "$(nat "$SRC")" --jury --edge --fmt
               --exclude 'edge_(noise|music|short_da|stereo48k|pcm8bit)|__(ogg|flac|m4a|amr)')
  show "$PY" tools/phone_prepare.py "${pargs[@]}"   # локально; выполняется и в --dry-run
  "$PY" tools/phone_prepare.py "${pargs[@]}" || die "phone_prepare.py не отработал"
  local files=("$STAGE"/push/*) natfiles=() f
  for f in "${files[@]}"; do natfiles+=("$(nat "$f")"); done
  xa shell mkdir -p "$MANUAL_REMOTE"
  xa push "${natfiles[@]}" "$MANUAL_REMOTE/" | tail -n 1
  # чтобы файлы появились в SAF/«Аудио»: медиасканер (best effort, разные API на разных Android)
  for f in "${files[@]}"; do
    xa shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$MANUAL_REMOTE/$(basename "$f")" >/dev/null
  done
  xa shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null
  say "в $MANUAL_REMOTE: $(for f in "${files[@]}"; do basename "$f"; done | tr '\n' ' ')"
  say "дальше — чек-лист tools/phone_airplane_check.md; после экспорта JSON в «Загрузки»: tools/phone_bench.sh --manual-collect"
}

mode_manual_collect() {
  check_device
  local stamp; stamp=$(date +%Y%m%d_%H%M%S)
  RUN=${OUT:-out/phone/${stamp}_manual}
  mkdir -p "$RUN/out" "$RUN/refs"
  local list; list=$(sh_ "ls /sdcard/Download/*.json $MANUAL_REMOTE/*.json 2>/dev/null")
  [ "$DRY" = 1 ] && list="/sdcard/Download/distinct_2spk_000.json"
  [ -n "$list" ] || die "в /sdcard/Download нет *.json — экспортируйте из приложения («Экспорт JSON» → Загрузки)"
  local p b n rc=0
  while read -r p; do
    [ -n "$p" ] || continue
    b=$(basename "$p"); n=${b%.json}; n=${n% (*)}; n=${n%-[0-9]}   # «x (1).json» / «x-1.json» → x
    xa pull "$p" "$(nat "$RUN/out/$n.json")" </dev/null >/dev/null
    if [ -f "$SRC/$n.json" ]; then
      cp "$SRC/$n.json" "$RUN/refs/"; [ -f "$SRC/$n.meta.json" ] && cp "$SRC/$n.meta.json" "$RUN/refs/"
    fi
    say "$b → $n.json"
    x "$PY" tools/validate_json.py "$(nat "$RUN/out/$n.json")" || rc=1
  done <<< "$list"
  if ls "$RUN/refs"/*.json >/dev/null 2>&1 || [ "$DRY" = 1 ]; then
    say "=== report.py (WER/DER по экспорту из UI; RTF — из секундомера, см. чек-лист) ==="
    x "$PY" tools/report.py "$(nat "$RUN/refs")" "$(nat "$RUN/out")" --csv "$(nat "$RUN/report.csv")" --md "$(nat "$RUN/report.md")" || rc=1
  fi
  say "результаты: $RUN"
  return $rc
}

case "$MODE" in
  bench) mode_bench ;;
  manual-push) mode_manual_push ;;
  manual-collect) mode_manual_collect ;;
esac
