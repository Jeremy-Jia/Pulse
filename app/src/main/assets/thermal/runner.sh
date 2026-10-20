#!/system/bin/sh
# Pulse 1.5: verified normal-profile CPU curves; guards outlive the UI.
# Run explicitly with /system/bin/sh; do not depend on a root manager's ash mode.
umask 077
B=/data/adb/pulse-thermal
SELF=$B/runner.sh
HOOK=/data/adb/service.d/70-pulse-thermal.sh
OVERRIDE=/data/vendor/thermal/config/thermal-normal.conf
ACTIVE=/data/vendor/thermal/decrypt.txt
P0=/sys/devices/system/cpu/cpufreq/policy0
P3=/sys/devices/system/cpu/cpufreq/policy3
P6=/sys/devices/system/cpu/cpufreq/policy6
TM=/sys/class/thermal/thermal_message
ORIGINAL=4a0fa3e62b1106b4f30df355579e633f0426267e39c580dd27c0a44ce71f3e2d
ORIGINAL_CIPHER=c121ffb850df822c8d28ec18fc6736a748d120affe610da07d302cf95b780641
BINARY=d6ddd1c6fe212e79d59a587b41212eaebd8873079c0a27f8161c6a5f1ea76075
DEVICE=$(getprop ro.product.device)
RUNTIME_MOUNT=0; GROUPS='0 6'; FACTORY0=3532800; FACTORY6=4320000
if [ "$DEVICE" = hongkong ]; then
  RUNTIME_MOUNT=1; GROUPS='0 3 6'; FACTORY0=3744000; FACTORY3=4032000; FACTORY6=5011200
  ORIGINAL=89b19321791814e190ffa93ac5accb34f45bf21e2e673a678d7857ef24e3d7f6
  ORIGINAL_CIPHER=32e541070eb50a82911c4c4bcdde8f665dc8ea70756467fc11b6d0736ffd561a
  BINARY=292b6416c32d84e752a1f451ed008f43f24942951c1b77a6ae85819383a13814
fi
hash_file() { sha256sum "$1" 2>/dev/null | cut -d ' ' -f 1; }
number() { case "$1" in ''|*[!0-9]*) return 1;; esac; }
upnow() { read -r now rest < /proc/uptime; now=${now%%.*}; }
error() { echo "ERROR $*"; exit 1; }
event() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $*" >> "$D/events.txt"; }
phase() { echo "$1" > "$D/phase.new"; mv "$D/phase.new" "$D/phase"; }
select_session() {
  number "$1" || error BAD_SESSION
  [ "${#1}" -le 20 ] || error BAD_SESSION
  ID=$1; D=$B/sessions/$ID
  [ -d "$D" ] || error MISSING_SESSION
  . "$D/policy.env" || error MISSING_POLICY
  cap3=${cap3:-0}
  [ ! -f "$D/runtime.env" ] || . "$D/runtime.env"
  for value in "$battery_limit" "$body_limit" "$cpu_limit" "$duration" "$resume_boot" "$cap0" "$cap6" "$owner_uid"; do
    number "$value" || error INVALID_POLICY
  done
  [ "$battery_limit" -ge 350 ] && [ "$battery_limit" -le 500 ] || error INVALID_BATTERY_LIMIT
  [ "$body_limit" -ge 35000 ] && [ "$body_limit" -le 50000 ] || error INVALID_BODY_LIMIT
  [ "$cpu_limit" = 0 ] || { [ "$cpu_limit" -ge 70000 ] && [ "$cpu_limit" -le 110000 ]; } || error INVALID_CPU_LIMIT
  [ "$duration" -le 86400 ] || error INVALID_DURATION
  [ "$resume_boot" = 0 ] || { [ "$resume_boot" = 1 ] && [ "$duration" = 0 ]; } || error INVALID_RESUME
  [ "$RUNTIME_MOUNT" = 0 ] || [ "$resume_boot" = 0 ] || error TEMP_ROOT_BOOT_RESUME_UNAVAILABLE
  case "$trial_hash:$trial_cipher_hash" in *[!a-f0-9:]*) error INVALID_HASH;; esac
  [ "${#trial_hash}" = 64 ] && [ "${#trial_cipher_hash}" = 64 ] || error INVALID_HASH
  case "$mode" in fullmax|plus25|boost|custom) :;; *) error INVALID_MODE;; esac
}
discover() {
  sensors=''
  for zone in /sys/class/thermal/thermal_zone*; do
    type=''; read -r type < "$zone/type" 2>/dev/null
    case "$type" in cpu-*) sensors="$sensors $zone/temp";; esac
  done
}
readings() {
  hot=0; bat=0; body=0
  [ -n "$sensors" ] || return 1
  for f in $sensors; do
    value=''; read -r value < "$f" 2>/dev/null
    number "$value" || return 1
    [ "$value" -le 150000 ] || return 1
    [ "$value" -gt "$hot" ] && hot=$value
  done
  read -r bat < /sys/class/power_supply/battery/temp || return 1
  read -r body < "$TM/board_sensor_temp" || return 1
  number "$bat" && number "$body" || return 1
  [ "$bat" -le 1000 ] && [ "$body" -le 100000 ] || return 1
  read -r freq0 < "$P0/scaling_cur_freq"
  read -r freq6 < "$P6/scaling_cur_freq"
  read -r max0 < "$P0/scaling_max_freq"
  read -r max6 < "$P6/scaling_max_freq"
  freq3=0; max3=0
  if [ "$RUNTIME_MOUNT" = 1 ]; then read -r freq3 < "$P3/scaling_cur_freq"; read -r max3 < "$P3/scaling_max_freq"; fi
  read -r scene < "$TM/sconfig"
  read -r balance < "$TM/balance_mode"
  current=''; voltage=''
  read -r current < /sys/class/power_supply/battery/current_now
  read -r voltage < /sys/class/power_supply/battery/voltage_now
  upnow
}
within_limits() {
  reason=SENSOR_ERROR
  readings || return 1
  [ "$bat" -lt "$battery_limit" ] || { reason=BATTERY_LIMIT; return 1; }
  [ "$body" -lt "$body_limit" ] || { reason=BODY_LIMIT; return 1; }
  [ "$cpu_limit" = 0 ] || [ "$hot" -lt "$cpu_limit" ] || { reason=CPU_LIMIT; return 1; }
}
install() {
  cp "$1" "$D/pending.conf" && chown root:system "$D/pending.conf" && chmod 644 "$D/pending.conf" &&
    chcon u:object_r:thermal_data_file:s0 "$D/pending.conf" && mv "$D/pending.conf" "$OVERRIDE" || return 1
  # The firmware directory watcher reacts to IN_MODIFY, not rename alone.
  dd if="$1" of="$OVERRIDE" bs=1 count=1 conv=notrunc 2>/dev/null
}
mount_id() { awk '$5=="/data/vendor/thermal/config" {print $1}' /proc/self/mountinfo | tail -n 1; }
mount_runtime() {
  [ "$RUNTIME_MOUNT" = 1 ] || return 0
  [ -z "$(mount_id)" ] || error EXISTING_OVERRIDE
  [ -z "$(ls -A /data/vendor/thermal/config)" ] || error EXISTING_OVERRIDE
  mkdir "$D/runtime-config" && chown root:system "$D/runtime-config" && chmod 771 "$D/runtime-config" &&
    chcon u:object_r:thermal_data_file:s0 "$D/runtime-config" || return 1
  mount --bind "$D/runtime-config" /data/vendor/thermal/config || return 1
  mount_id > "$D/mount.id"
  [ -s "$D/mount.id" ] || return 1
  # New directory inode: re-arm the daemon's watcher in the global namespace.
  old_daemon=$(pidof mi_thermald)
  setprop ctl.restart mi_thermald
  ready_daemon=0
  for retry in $(seq 1 8); do
    new_daemon=$(pidof mi_thermald)
    if [ -n "$new_daemon" ] && [ "$new_daemon" != "$old_daemon" ] && [ "$(getprop init.svc.mi_thermald)" = running ]; then ready_daemon=1; break; fi
    sleep 1
  done
  [ "$ready_daemon" = 1 ] || return 1
  # init's running state precedes the inotify thread; deliver IN_MODIFY after it starts.
  sleep 1
}
unmount_runtime() {
  [ "$RUNTIME_MOUNT" = 1 ] || return 0
  actual=$(mount_id); expected=$(cat "$D/mount.id" 2>/dev/null)
  [ -n "$actual" ] || return 0
  [ -n "$expected" ] && [ "$actual" = "$expected" ] || return 1
  umount /data/vendor/thermal/config || return 1
  setprop ctl.restart mi_thermald
}
stop_peer() {
  role=$1; peer=''; read -r peer < "$D/$role.pid" 2>/dev/null
  number "$peer" || return 0
  [ "$peer" != "$$" ] || return 0
  cat "/proc/$peer/cmdline" 2>/dev/null | tr '\0' ' ' | grep -qF "$SELF $role $ID" || return 0
  kill -TERM "$peer" 2>/dev/null
  for stop_tick in $(seq 1 20); do
    cat "/proc/$peer/cmdline" 2>/dev/null | tr '\0' ' ' | grep -qF "$SELF $role $ID" || return 0
    sleep .1
  done
  kill -KILL "$peer" 2>/dev/null
}
restore() {
  why=$1
  [ "$(cat "$D/phase" 2>/dev/null)" = restored ] && return 0
  if ! mkdir "$D/restore.lock" 2>/dev/null; then
    owner=''; read -r owner < "$D/restore.lock/pid" 2>/dev/null
    if number "$owner" && kill -0 "$owner" 2>/dev/null; then
      for retry in $(seq 1 15); do
        [ "$(cat "$D/phase" 2>/dev/null)" = restored ] && return 0
        sleep 1
      done
      return 1
    fi
    rm -f "$D/restore.lock/pid"; rmdir "$D/restore.lock" 2>/dev/null
    mkdir "$D/restore.lock" || return 1
  fi
  echo $$ > "$D/restore.lock/pid"
  phase restoring
  # An OTA must never cause an old encoded profile to be installed into new
  # firmware. Retire only our own override and let the new factory daemon load.
  if [ "$(hash_file /vendor/bin/mi_thermald)" != "$BINARY" ] || [ "$(hash_file /odm/etc/thermal-normal.conf)" != "$ORIGINAL_CIPHER" ]; then
    existing=$(hash_file "$OVERRIDE")
    if [ "$existing" = "$trial_cipher_hash" ] || [ "$existing" = "$ORIGINAL_CIPHER" ]; then
      mv "$OVERRIDE" "$D/retired-after-update.bin" || return 1
      setprop ctl.restart mi_thermald
    fi
    [ "$RUNTIME_MOUNT" = 0 ] || unmount_runtime || return 1
    upnow; echo "$now" > "$D/stop.uptime"
    echo FIRMWARE_CHANGED > "$D/reason"; phase restored; event 'FIRMWARE_CHANGED: old profile retired'
    rm -f "$HOOK" "$D/restore.lock/pid"; rmdir "$D/restore.lock" 2>/dev/null
    stop_peer watch; stop_peer sentry
    return 0
  fi
  result=0
  if [ -f "$OVERRIDE" ]; then
    existing=$(hash_file "$OVERRIDE")
    if [ "$existing" != "$trial_cipher_hash" ] && [ "$existing" != "$ORIGINAL_CIPHER" ]; then
      event 'FOREIGN_OVERRIDE_NOT_OVERWRITTEN'; result=1
    else
      install "$D/original.bin" || result=1
      verified=0
      for retry in $(seq 1 8); do
        active_hash=$(hash_file "$ACTIVE")
        if [ "$active_hash" = "$ORIGINAL" ] || { [ "$(cat "$TM/sconfig")" != 0 ] && [ "$active_hash" != "$trial_hash" ]; }; then verified=1; break; fi
        sleep 1
      done
      if [ "$verified" != 1 ]; then
        setprop ctl.restart mi_thermald
        for retry in $(seq 1 8); do
          [ "$(hash_file "$ACTIVE")" = "$ORIGINAL" ] && { verified=1; break; }
          sleep 1
        done
      fi
      if [ "$verified" = 1 ] && [ "$result" = 0 ]; then
        mv "$OVERRIDE" "$D/restored-override.bin" || result=1
      else result=1; fi
    fi
  elif [ "$(hash_file "$ACTIVE")" = "$trial_hash" ]; then
    # An externally removed override can leave the daemon's cached policy active.
    install "$D/original.bin" || result=1
    sleep 1
    if [ "$(hash_file "$ACTIVE")" = "$ORIGINAL" ]; then mv "$OVERRIDE" "$D/restored-override.bin" || result=1; else result=1; fi
  fi
  # Release only this app's user frequency vote; the verified factory profile
  # supplies its own limits, including temperature-dependent reductions.
  echo "$FACTORY0" > "$P0/scaling_max_freq"
  echo "$FACTORY6" > "$P6/scaling_max_freq"
  if [ "$RUNTIME_MOUNT" = 1 ]; then
    echo "$FACTORY3" > "$P3/scaling_max_freq"
    unmount_runtime || result=1
    if [ "$result" = 0 ]; then
      verified=0
      for retry in $(seq 1 8); do [ "$(hash_file "$ACTIVE")" != "$trial_hash" ] && [ "$(getprop init.svc.mi_thermald)" = running ] && { verified=1; break; }; sleep 1; done
      [ "$verified" = 1 ] || result=1
    fi
  fi
  if [ "$result" = 0 ]; then
    upnow; echo "$now" > "$D/stop.uptime"
    echo "$why" > "$D/reason"; phase restored; event "RESTORED $why"
    rm -f "$HOOK"
    stop_peer watch
    stop_peer sentry
  else phase recovery_failed; echo RECOVERY_FAILED > "$D/reason"; event 'RECOVERY_FAILED'; fi
  rm -f "$D/restore.lock/pid"; rmdir "$D/restore.lock" 2>/dev/null
  return "$result"
}
snapshot() {
  state=$(cat "$D/phase" 2>/dev/null); [ -n "$state" ] || state=idle
  started=0; read -r started < "$D/start.uptime" 2>/dev/null
  upnow
  [ "$state" != restored ] || read -r now < "$D/stop.uptime" 2>/dev/null
  elapsed=$((now-started)); [ "$elapsed" -ge 0 ] || elapsed=0
  remaining=-1; [ "$duration" = 0 ] || remaining=$((duration-elapsed))
  echo "state=$state"; echo "session=$ID"; echo "mode=$mode"
  echo "boost_percent=${boost_percent:-25}"
  echo "elapsed=$elapsed"; echo "remaining=$remaining"; echo "resume=$resume_boot"
  echo "cpu_mc=$hot"; echo "battery_dc=$bat"; echo "body_mc=$body"
  echo "freq0=$freq0"; echo "freq6=$freq6"; echo "max0=$max0"; echo "max6=$max6"
  echo "freq3=$freq3"; echo "max3=$max3"; echo "scene=$scene"; echo "balance=$balance"
  echo "battery_limit=$battery_limit"; echo "body_limit=$body_limit"; echo "cpu_limit=$cpu_limit"
  echo "reason=$(cat "$D/reason" 2>/dev/null)"; echo "current_ua=$current"; echo "voltage_uv=$voltage"
}
command=$1
case "$command" in
  status)
    discover; readings
    if [ -f "$B/current" ]; then
      read -r id < "$B/current"; select_session "$id"
      if [ "$RUNTIME_MOUNT" = 1 ] && [ "$(cat "$D/boot.id" 2>/dev/null)" != "$(cat /proc/sys/kernel/random/boot_id)" ]; then
        [ -n "$(mount_id)" ] || { echo REBOOT_RECOVERY > "$D/reason"; phase restored; }
      fi
      snapshot
    else
      echo state=idle; echo "cpu_mc=$hot"; echo "battery_dc=$bat"; echo "body_mc=$body"
      echo "freq0=$freq0"; echo "freq6=$freq6"; echo "max0=$max0"; echo "max6=$max6"
      echo "freq3=$freq3"; echo "max3=$max3"
    fi
    exit;;
  export)
    [ -f "$B/current" ] || exit 0
    read -r id < "$B/current"; select_session "$id"
    if [ -f "$D/telemetry.1.csv" ]; then cat "$D/telemetry.1.csv"; sed '1d' "$D/telemetry.csv"; else cat "$D/telemetry.csv" 2>/dev/null; fi
    exit;;
  boot)
    [ -f "$B/current" ] || exit 0
    read -r id < "$B/current"; select_session "$id"
    read -r saved_boot < "$D/boot.id"; read -r current_boot < /proc/sys/kernel/random/boot_id
    [ "$saved_boot" != "$current_boot" ] || exit 0
    state=$(cat "$D/phase" 2>/dev/null)
    [ "$state" != restored ] || { rm -f "$HOOK"; exit 0; }
    want_resume=$resume_boot
    rm -f "$D/restore.lock/pid"; rmdir "$D/restore.lock" 2>/dev/null
    for tick in $(seq 1 60); do [ "$(getprop init.svc.mi_thermald)" = running ] && break; sleep 1; done
    restore REBOOT_RECOVERY || exit 1
    if [ "$want_resume" = 1 ] && [ "$state" = active ]; then
      for tick in $(seq 1 120); do [ "$(getprop sys.boot_completed)" = 1 ] && break; sleep 1; done
      # Android's BootReceiver owns notification startup. Never make an early
      # root boot script wait for a Binder round trip into the same app.
      /system/bin/sh "$SELF" start "$id" resume || exit 1
    fi
    exit 0;;
esac
select_session "$2"
case "$command" in
  restore) restore "${3:-MANUAL}"; exit $?;;
  sentry)
    read -r start < "$D/start.uptime"
    while :; do
      state=$(cat "$D/phase" 2>/dev/null); [ "$state" != restored ] || exit 0
      upnow
      watcher=''; read -r watcher < "$D/watch.pid" 2>/dev/null
      if [ "$((now-start))" -gt 5 ]; then
        if ! number "$watcher" || ! kill -0 "$watcher" 2>/dev/null || ! cat "/proc/$watcher/cmdline" 2>/dev/null | tr '\0' ' ' | grep -qF "$SELF watch $ID"; then
          restore WATCHDOG_LOST; exit
        fi
      fi
      if [ "$duration" != 0 ] && [ "$((now-start))" -ge "$((duration+10))" ]; then restore DEADLINE_FALLBACK; exit; fi
      sleep 2
    done;;
  watch)
    trap 'restore WATCHDOG_EXIT' EXIT
    trap 'exit 130' HUP INT TERM
    discover
    for tick in $(seq 1 15); do [ -f "$D/ready" ] && break; sleep 1; done
    [ -f "$D/ready" ] || { restore ACTIVATION_TIMEOUT; exit 1; }
    read -r start < "$D/start.uptime"; tick=0
    echo 'uptime_s,elapsed_s,cpu_hottest_mc,battery_dc,body_mc,current0_khz,current6_khz,max0_khz,max6_khz,current_ua,voltage_uv,current3_khz,max3_khz,scene,state' > "$D/telemetry.csv"
    while :; do
      state=$(cat "$D/phase"); case "$state" in active|paused) :;; *) break;; esac
      within_limits; valid=$?
      echo "$now,$((now-start)),$hot,$bat,$body,$freq0,$freq6,$max0,$max6,$current,$voltage,$freq3,$max3,$scene,$state" >> "$D/telemetry.csv"
      snapshot > "$D/snapshot.new"; mv "$D/snapshot.new" "$D/snapshot"
      [ "$valid" = 0 ] || { event "LIMIT cpu=$hot battery=$bat body=$body"; restore "$reason"; exit; }
      [ "$duration" = 0 ] || [ "$((now-start))" -lt "$duration" ] || { restore TIMER; exit; }
      if [ "$((tick%5))" = 0 ]; then
        [ "$(getprop init.svc.mi_thermald)" = running ] && [ "$(getprop init.svc.vendor.thermal-hal)" = running ] || { restore THERMAL_SERVICE_STOPPED; exit; }
        active_hash=$(hash_file "$ACTIVE")
        [ "$(hash_file "$OVERRIDE")" = "$trial_cipher_hash" ] || { restore CONFIG_CHANGED; exit; }
        if [ "$active_hash" = "$trial_hash" ]; then
          if [ "$state" = paused ]; then phase active; echo NONE > "$D/reason"; event "RESUMED scene=$scene balance=$balance"; fi
        elif [ "$duration" = 0 ]; then
          if [ "$state" != paused ]; then phase paused; echo SCENE_PAUSED > "$D/reason"; event "PAUSED scene=$scene balance=$balance hash=$active_hash"; fi
          # Only reapply when the exact verified normal profile is back. Never
          # overwrite a game/charging profile merely because a scene number changed.
          if [ "$active_hash" = "$ORIGINAL" ]; then install "$D/trial.bin" || { restore ACTIVATION_FAILED; exit; }; fi
        else restore CONFIG_CHANGED; exit; fi
      fi
      if [ "$((tick%15))" = 0 ]; then
        cmd package path dev.pulse.monitor 2>/dev/null | grep -q '^package:' || { restore APP_REMOVED; exit; }
      fi
      if [ "$((tick%120))" = 119 ] && [ "$(wc -c < "$D/telemetry.csv")" -gt 524288 ]; then
        mv "$D/telemetry.csv" "$D/telemetry.1.csv"
        echo 'uptime_s,elapsed_s,cpu_hottest_mc,battery_dc,body_mc,current0_khz,current6_khz,max0_khz,max6_khz,current_ua,voltage_uv,current3_khz,max3_khz,scene,state' > "$D/telemetry.csv"
      fi
      tick=$((tick+1)); sleep 1
    done
    exit;;
  start) :;;
  *) error BAD_COMMAND;;
esac

case "$DEVICE" in dada|hongkong) :;; *) error UNSUPPORTED_DEVICE;; esac
[ "$(hash_file /vendor/bin/mi_thermald)" = "$BINARY" ] || error FIRMWARE_CHANGED
[ "$(hash_file /odm/etc/thermal-normal.conf)" = "$ORIGINAL_CIPHER" ] || error FIRMWARE_CHANGED
[ "$(hash_file "$D/original.bin")" = "$ORIGINAL_CIPHER" ] || error INVALID_BACKUP
[ "$(hash_file "$D/trial.bin")" = "$trial_cipher_hash" ] || error INVALID_TRIAL
[ "$(hash_file "$ACTIVE")" = "$ORIGINAL" ] || error ACTIVE_PROFILE_MISMATCH
[ ! -e "$OVERRIDE" ] || error EXISTING_OVERRIDE
[ "$(getprop init.svc.mi_thermald)" = running ] && [ "$(getprop init.svc.vendor.thermal-hal)" = running ] || error THERMAL_SERVICE_NOT_RUNNING
for policy in $GROUPS; do
  eval "cap=\$cap$policy"
  node=/sys/devices/system/cpu/cpufreq/policy$policy
  if ! cat "$node/scaling_available_frequencies" | tr ' ' '\n' | grep -qx "$cap"; then
    [ "$DEVICE" = hongkong ] && [ "$policy" = 6 ] && [ "$(cat "$node/boost")" = 1 ] &&
      [ "$(cat /sys/devices/system/cpu/cpufreq/boost)" = 1 ] &&
      cat "$node/scaling_boost_frequencies" | tr ' ' '\n' | grep -qx "$cap" || error BOOST_UNAVAILABLE
  fi
done
discover
within_limits || error "PREFLIGHT_$reason"
if [ -f "$B/current" ]; then
  read -r previous < "$B/current"
  old_state=$(cat "$B/sessions/$previous/phase" 2>/dev/null)
  case "$old_state" in active|paused|starting|restoring|recovery_failed) error SESSION_ALREADY_ACTIVE;; esac
fi
[ ! -f "$D/ready" ] || [ "$3" = resume ] || error SESSION_ALREADY_USED
rm -f "$D/ready"
rm -f "$D/stop.uptime"
read -r initial_balance < "$TM/balance_mode"
echo "initial_balance=$initial_balance" > "$D/runtime.env"
upnow; echo "$now" > "$D/start.uptime"
cat /proc/sys/kernel/random/boot_id > "$D/boot.id"
echo "$ID" > "$B/current.new"; mv "$B/current.new" "$B/current"
echo NONE > "$D/reason"; phase starting
if [ "$RUNTIME_MOUNT" = 0 ]; then
  mkdir -p /data/adb/service.d || error BOOT_RECOVERY_FAILED
  printf '#!/system/bin/sh\n/system/bin/sh /data/adb/pulse-thermal/runner.sh boot >> /data/adb/pulse-thermal/boot.log 2>&1\n' > "$HOOK"
  chmod 700 "$HOOK" || error BOOT_RECOVERY_FAILED
fi
trap '[ -f "$D/ready" ] || restore ACTIVATION_FAILED' EXIT
trap 'exit 130' HUP INT TERM
nohup /system/bin/sh "$SELF" watch "$ID" > "$D/watch.log" 2>&1 </dev/null &
echo $! > "$D/watch.pid"
nohup /system/bin/sh "$SELF" sentry "$ID" > "$D/sentry.log" 2>&1 </dev/null &
echo $! > "$D/sentry.pid"
mount_runtime || error RUNTIME_MOUNT_FAILED
install "$D/trial.bin" || error INSTALL_FAILED
loaded=0
for tick in $(seq 1 6); do [ "$(hash_file "$ACTIVE")" = "$trial_hash" ] && { loaded=1; break; }; sleep 1; done
[ "$loaded" = 1 ] || error VERIFY_FAILED
echo "$cap0" > "$P0/scaling_max_freq"
echo "$cap6" > "$P6/scaling_max_freq"
[ "$RUNTIME_MOUNT" = 0 ] || echo "$cap3" > "$P3/scaling_max_freq"
phase active; event "ACTIVE mode=$mode duration=$duration resume=$resume_boot"
echo ready > "$D/ready"
echo "OK ACTIVE $ID"
