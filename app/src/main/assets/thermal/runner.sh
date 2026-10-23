#!/system/bin/sh
# Pulse 1.9.1: detached guards, process identity and monotonic heartbeats.
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
GPU=/sys/class/kgsl/kgsl-3d0
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
body_feedback() { case "$body_mode" in regulate|performance) return 0;; *) return 1;; esac; }
guard_phase() { case "$1" in active|starting|paused|thermal_paused|thermal_pausing) return 0;; *) return 1;; esac; }
birth() { awk '{print $22}' "/proc/$1/stat" 2>/dev/null; }
peer_identity() {
  peer=''; saved_birth=''
  [ -s "$D/$1.pid" ] && [ -s "$D/$1.birth" ] || return 1
  read -r peer < "$D/$1.pid" 2>/dev/null
  read -r saved_birth < "$D/$1.birth" 2>/dev/null
  number "$peer" && number "$saved_birth" && [ "$(birth "$peer")" = "$saved_birth" ] || return 1
  tr '\0' ' ' < "/proc/$peer/cmdline" 2>/dev/null | grep -qF "$SELF $1 $ID "
}
peer_healthy() {
  peer_identity "$1" || return 1
  beat=''; read -r beat < "$D/$1.beat" 2>/dev/null
  # Read the clock AFTER the heartbeat. A peer can publish in the next second
  # while its identity is checked; the caller's earlier timestamp is obsolete.
  upnow
  number "$beat" && [ "$beat" -le "$now" ] && [ "$((now-beat))" -le 30 ]
}
heartbeat() {
  upnow; previous_beat=$now
  [ ! -s "$D/$command.beat" ] || read -r previous_beat < "$D/$command.beat"
  number "$previous_beat" || previous_beat=$now
  loop_gap=$((now-previous_beat))
  echo "$now" > "$D/$command.beat.new"; mv "$D/$command.beat.new" "$D/$command.beat"
}
starting_grace() {
  [ "$state" = starting ] || return 1
  activation_start=''; read -r activation_start < "$D/start.uptime" 2>/dev/null
  number "$activation_start" && [ "$((now-activation_start))" -le 30 ]
}
detach_guard() {
  # setsid alone does not escape Android's UID cgroup kill/freeze operations.
  for cg in /sys/fs/cgroup/cgroup.procs /dev/cpuset/cgroup.procs /dev/cpuctl/cgroup.procs /dev/blkio/cgroup.procs /sys/fs/cgroup/freezer/cgroup.procs; do
    [ ! -e "$cg" ] || { echo $$ > "$cg" || return 1; }
  done
  echo -900 > /proc/$$/oom_score_adj 2>/dev/null
  echo $$ > "$D/$command.pid.new"; mv "$D/$command.pid.new" "$D/$command.pid"
  birth $$ > "$D/$command.birth.new"; mv "$D/$command.birth.new" "$D/$command.birth"
  cat /proc/$$/cgroup > "$D/$command.cgroup"
  heartbeat
}
select_session() {
  number "$1" || error BAD_SESSION
  [ "${#1}" -le 20 ] || error BAD_SESSION
  ID=$1; D=$B/sessions/$ID
  [ -d "$D" ] || error MISSING_SESSION
  . "$D/policy.env" || error MISSING_POLICY
  cap3=${cap3:-0}
  thermal_auto_resume=${thermal_auto_resume:-0}; battery_resume=${battery_resume:-420}; cpu_resume=${cpu_resume:-75000}
  body_mode=${body_mode:-stop}; body_resume=${body_resume:-49000}
  gpu_enabled=${gpu_enabled:-0}; gpu_cap_hz=${gpu_cap_hz:-0}; gpu_level=${gpu_level:-0}
  [ ! -f "$D/runtime.env" ] || . "$D/runtime.env"
  for value in "$battery_limit" "$body_limit" "$cpu_limit" "$duration" "$resume_boot" "$cap0" "$cap6" "$owner_uid"; do
    number "$value" || error INVALID_POLICY
  done
  [ "$battery_limit" -ge 350 ] && [ "$battery_limit" -le 500 ] || error INVALID_BATTERY_LIMIT
  case "$body_mode" in
    performance)
      [ "$DEVICE" = hongkong ] && [ "$body_limit" -ge 35000 ] && [ "$body_limit" -le 52000 ] || error INVALID_BODY_LIMIT
      body_resume=$body_limit;;
    regulate)
      [ "$DEVICE" = hongkong ] && number "$body_resume" && [ "$body_limit" -ge 35000 ] && [ "$body_limit" -le 52000 ] &&
        [ "$body_resume" -ge 32000 ] && [ "$body_resume" -lt "$body_limit" ] || error INVALID_BODY_LIMIT;;
    stop) [ "$body_limit" -ge 35000 ] && [ "$body_limit" -le 50000 ] || error INVALID_BODY_LIMIT;;
    *) error INVALID_BODY_MODE;;
  esac
  [ "$cpu_limit" = 0 ] || { [ "$cpu_limit" -ge 70000 ] && [ "$cpu_limit" -le 110000 ]; } || error INVALID_CPU_LIMIT
  case "$thermal_auto_resume" in 0) :;; 1)
    [ "$DEVICE" = hongkong ] && body_feedback && number "$battery_resume" && number "$cpu_resume" &&
      [ "$battery_resume" -ge 300 ] && [ "$battery_resume" -lt "$battery_limit" ] && [ "$cpu_resume" -ge 60000 ] &&
      { [ "$cpu_limit" = 0 ] || [ "$cpu_resume" -lt "$cpu_limit" ]; } || error INVALID_AUTO_RESUME;;
    *) error INVALID_AUTO_RESUME;; esac
  [ "$duration" -le 86400 ] || error INVALID_DURATION
  [ "$resume_boot" = 0 ] || { [ "$resume_boot" = 1 ] && [ "$duration" = 0 ]; } || error INVALID_RESUME
  [ "$RUNTIME_MOUNT" = 0 ] || [ "$resume_boot" = 0 ] || error TEMP_ROOT_BOOT_RESUME_UNAVAILABLE
  case "$trial_hash:$trial_cipher_hash" in *[!a-f0-9:]*) error INVALID_HASH;; esac
  [ "${#trial_hash}" = 64 ] && [ "${#trial_cipher_hash}" = 64 ] || error INVALID_HASH
  case "$mode" in fullmax|plus25|boost|custom) :;; *) error INVALID_MODE;; esac
  case "$gpu_enabled" in 0) :;; 1)
    [ "$DEVICE" = hongkong ] || error GPU_UNSUPPORTED
    number "$gpu_cap_hz" && number "$gpu_level" && [ "$gpu_level" -le 25 ] || error INVALID_GPU_POLICY;;
    *) error INVALID_GPU_POLICY;; esac
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
  gpu_hz=0; gpu_max_hz=0; gpu_thermal_level=0
  if [ "$RUNTIME_MOUNT" = 1 ]; then
    read -r gpu_hz < "$GPU/gpuclk"; read -r gpu_max_hz < "$GPU/max_gpuclk"; read -r gpu_thermal_level < "$GPU/thermal_pwrlevel"
    # max_gpuclk exposes the thermal vote on this driver, not max_pwrlevel.
    # Report the stricter of both; current execution frequency remains separate.
    if gpu_user_read; then
      user_max_hz=$(cat "$GPU/gpu_available_frequencies" | xargs | cut -d ' ' -f "$((gpu_user_level+1))")
      if number "$user_max_hz" && [ "$user_max_hz" -gt 0 ] && [ "$user_max_hz" -lt "$gpu_max_hz" ]; then gpu_max_hz=$user_max_hz; fi
    fi
  fi
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
  body_feedback || [ "$body" -lt "$body_limit" ] || { reason=BODY_LIMIT; return 1; }
  [ "$cpu_limit" = 0 ] || [ "$hot" -lt "$cpu_limit" ] || { reason=CPU_LIMIT; return 1; }
}
load_cooling() {
  cooling_level=0; cooling_since=0
  if [ -f "$D/cooling" ]; then
    read -r cooling_level cooling_since < "$D/cooling" || return 1
    number "$cooling_level" && number "$cooling_since" && [ "$cooling_level" -le 4 ] || return 1
  fi
}
# Hysteresis, monotonic time and discrete hardware-supported frequency caps.
choose_cooling() {
  next_level=$cooling_level
  if [ "$body" -ge "$body_limit" ]; then
    minimum_level=$((1+(body-body_limit)/1000)); [ "$minimum_level" -le 4 ] || minimum_level=4
    [ "$next_level" -ge "$minimum_level" ] || next_level=$minimum_level
    if [ "$next_level" = "$cooling_level" ] && [ "$next_level" -lt 4 ] && [ "$((now-cooling_since))" -ge 5 ]; then next_level=$((next_level+1)); fi
  elif [ "$body_mode" = performance ]; then
    # Performance priority: the first below-limit sample releases every extra cap.
    # No 49-52 degree hold band and no delayed recovery staircase.
    next_level=0
  elif [ "$body" -le "$body_resume" ] && [ "$next_level" -gt 0 ] && [ "$((now-cooling_since))" -ge 5 ]; then
    next_level=$((next_level-1))
  fi
}
# A prewritten inode makes the lock owner visible atomically, including SIGKILL.
control_lock() {
  echo "$$ $(birth $$)" > "$D/control.$$"
  for lock_tick in $(seq 1 "${1:-30}"); do
    if ln "$D/control.$$" "$D/control.lock" 2>/dev/null; then rm -f "$D/control.$$"; return 0; fi
    lock_owner=''; lock_birth=''; read -r lock_owner lock_birth < "$D/control.lock" 2>/dev/null
    if number "$lock_owner" && { ! kill -0 "$lock_owner" 2>/dev/null || [ "$(birth "$lock_owner")" != "$lock_birth" ]; }; then rm -f "$D/control.lock"; fi
    sleep .1
  done
  rm -f "$D/control.$$"; return 1
}
control_unlock() { rm -f "$D/control.lock"; }
floor_frequency() {
  wanted=$1; shift; selected_frequency=0; lowest_frequency=2147483647
  for frequency in "$@"; do
    number "$frequency" || return 1
    [ "$frequency" -ge "$lowest_frequency" ] || lowest_frequency=$frequency
    if [ "$frequency" -le "$wanted" ] && [ "$frequency" -gt "$selected_frequency" ]; then selected_frequency=$frequency; fi
  done
  [ "$lowest_frequency" != 2147483647 ] || return 1
  [ "$selected_frequency" -gt 0 ] || selected_frequency=$lowest_frequency
}
gpu_user_read() {
  gpu_user_level=''; read -r gpu_user_level < "$GPU/max_pwrlevel" || return 1
  number "$gpu_user_level" && [ "$gpu_user_level" -le 25 ]
}
gpu_user_owned() { grep -qx "$gpu_user_level" "$D/gpu.votes" 2>/dev/null; }
release_gpu_vote() {
  body_feedback && [ "$gpu_enabled" = 1 ] && [ -f "$D/gpu.votes" ] || return 0
  number "$initial_gpu_user_level" && [ "$initial_gpu_user_level" -le 25 ] && gpu_user_read || return 1
  [ "$gpu_user_level" != "$initial_gpu_user_level" ] || return 0
  if gpu_user_owned; then
    echo "$initial_gpu_user_level" > "$GPU/max_pwrlevel" || return 1
    gpu_user_read && [ "$gpu_user_level" = "$initial_gpu_user_level" ] || return 1
  else event "FOREIGN_GPU_VOTE_NOT_OVERWRITTEN level=$gpu_user_level"; fi
}
release_cpu_votes() {
  for release_group in $GROUPS; do
    eval "release_node=\$P$release_group"
    # Use the currently exposed table, including after a firmware change.
    floor_frequency 2147483647 $(cat "$release_node/scaling_available_frequencies" "$release_node/scaling_boost_frequencies" 2>/dev/null) || return 1
    echo "$selected_frequency" > "$release_node/scaling_max_freq" || return 1
  done
}
gpu_target() {
  cooling_percent=$((100-next_level*20))
desired_gpu_level=$initial_gpu_user_level
if [ "$next_level" -gt 0 ]; then
  # Divide first: Android shell arithmetic must not overflow at MHz * percent.
  floor_frequency "$((gpu_cap_hz/100*cooling_percent))" $(cat "$GPU/gpu_available_frequencies") || return 1
  gpu_index=0
  for frequency in $(cat "$GPU/gpu_available_frequencies"); do
    if [ "$frequency" = "$selected_frequency" ]; then [ "$desired_gpu_level" -ge "$gpu_index" ] || desired_gpu_level=$gpu_index; break; fi
    gpu_index=$((gpu_index+1))
  done
fi
}
# Some device transitions reset user votes. Repair drift only while a cooling
# cap is required; normal below-limit operation never repeatedly pins maxima.
cooling_votes_drifted() {
  cooling_percent=$((100-next_level*20))
  if [ "$gpu_enabled" = 1 ]; then
    gpu_target && gpu_user_read || return 0
    [ "$gpu_user_level" = "$desired_gpu_level" ] || return 0
  fi
  for check_group in $GROUPS; do
    eval "check_node=\$P$check_group; check_cap=\$cap$check_group"
    floor_frequency "$((check_cap*cooling_percent/100))" $(cat "$check_node/scaling_available_frequencies" "$check_node/scaling_boost_frequencies" 2>/dev/null) || return 0
    check_max=''; read -r check_max < "$check_node/scaling_max_freq"
    number "$check_max" && [ "$check_max" -le "$selected_frequency" ] || return 0
  done
  return 1
}
write_cooling_votes() {
  cooling_percent=$((100-next_level*20))
  if [ "$gpu_enabled" = 1 ]; then
    gpu_user_read || return 1
    [ "$gpu_user_level" = "$initial_gpu_user_level" ] || gpu_user_owned || return 1
    gpu_target || return 1
    # Write-ahead ownership set also covers a crash between sysfs write and state save.
    grep -qx "$desired_gpu_level" "$D/gpu.votes" 2>/dev/null || echo "$desired_gpu_level" >> "$D/gpu.votes" || return 1
    echo "$desired_gpu_level" > "$GPU/max_pwrlevel" || return 1
    gpu_user_read && [ "$gpu_user_level" = "$desired_gpu_level" ] || return 1
  fi
  for group in $GROUPS; do
    eval "node=\$P$group; requested_cap=\$cap$group"
    floor_frequency "$((requested_cap*cooling_percent/100))" $(cat "$node/scaling_available_frequencies" "$node/scaling_boost_frequencies" 2>/dev/null) || return 1
    echo "$selected_frequency" > "$node/scaling_max_freq" || return 1
  done
}
update_cooling() {
  body_feedback || return 0
  control_lock || return 1
  [ ! -f "$D/stop.requested" ] || { control_unlock; return 0; }
  control_state=$(cat "$D/phase")
  case "$control_state" in active|starting|paused) :;; *) control_unlock; return 0;; esac
  if ! load_cooling; then control_unlock; return 1; fi
  choose_cooling
  [ "$control_state" != paused ] || next_level=0
  if [ "$next_level" != "$cooling_level" ] || [ "${1:-}" = initial ] || { [ "$next_level" -gt 0 ] && cooling_votes_drifted; }; then
    if ! write_cooling_votes; then control_unlock; return 1; fi
    transition_time=$cooling_since
    if [ "$next_level" != "$cooling_level" ] || [ "${1:-}" = initial ]; then transition_time=$now; fi
    echo "$next_level $transition_time" > "$D/cooling.new" && mv "$D/cooling.new" "$D/cooling" || { control_unlock; return 1; }
    event "BODY_CONTROL level=$next_level percent=$cooling_percent body=$body high=$body_limit resume=$body_resume"
  fi
  control_unlock
}
temperature_resume_ready() {
  [ "$bat" -le "$battery_resume" ] && [ "$body" -lt "$body_limit" ] &&
    { [ "$cpu_limit" = 0 ] || [ "$hot" -le "$cpu_resume" ]; }
}
emit_notice() {
  notice_seq=0; notice_kind=''
  [ ! -f "$D/notice" ] || read -r notice_seq notice_kind < "$D/notice"
  number "$notice_seq" || notice_seq=0
  echo "$((notice_seq+1)) $1" > "$D/notice.new"; mv "$D/notice.new" "$D/notice"
  # Wake our own foreground service; NotificationManager posts the system alert.
  # This never asks the Android process to reapply a policy or create a new session.
  nohup /system/bin/am start-foreground-service --user 0 -n dev.pulse.monitor/dev.pulse.thermal.ThermalService -a dev.pulse.thermal.GUARD_EVENT > "$D/notice.log" 2>&1 </dev/null &
}
pause_for_temperature() {
  pause_cause=$1
  control_lock || return 1
  [ ! -f "$D/stop.requested" ] || { control_unlock; return 0; }
  [ "$(hash_file "$OVERRIDE")" = "$trial_cipher_hash" ] || { control_unlock; return 1; }
  phase thermal_pausing
  if ! install "$D/original.bin"; then control_unlock; return 1; fi
  pause_loaded=0
  for pause_tick in $(seq 1 8); do
    pause_hash=$(hash_file "$ACTIVE")
    if [ "$pause_hash" = "$ORIGINAL" ] || { [ "$(cat "$TM/sconfig")" != 0 ] && [ "$pause_hash" != "$trial_hash" ]; }; then pause_loaded=1; break; fi
    sleep 1
  done
  if [ "$pause_loaded" != 1 ] || ! release_gpu_vote || ! release_cpu_votes; then control_unlock; return 1; fi
  upnow; echo "0 $now" > "$D/cooling.new"; mv "$D/cooling.new" "$D/cooling"
  echo "$pause_cause" > "$D/reason"
  if [ -f "$D/stop.requested" ]; then phase restoring; control_unlock; return 0; fi
  phase thermal_paused; event "THERMAL_PAUSED $pause_cause cpu=$hot battery=$bat body=$body"
  control_unlock; emit_notice PAUSED
}
resume_after_cooling() {
  control_lock || return 1
  [ ! -f "$D/stop.requested" ] || { control_unlock; return 0; }
  [ "$(cat "$D/phase")" = thermal_paused ] || { control_unlock; return 0; }
  if ! readings || ! temperature_resume_ready || [ "$(hash_file "$ACTIVE")" != "$ORIGINAL" ]; then control_unlock; return 0; fi
  if [ "$(hash_file /vendor/bin/mi_thermald)" != "$BINARY" ] || [ "$(hash_file /odm/etc/thermal-normal.conf)" != "$ORIGINAL_CIPHER" ] ||
     [ "$(hash_file "$OVERRIDE")" != "$ORIGINAL_CIPHER" ]; then control_unlock; return 1; fi
  phase starting; next_level=0
  if ! write_cooling_votes || ! install "$D/trial.bin"; then control_unlock; return 1; fi
  resume_loaded=0
  for resume_tick in $(seq 1 6); do
    [ "$(hash_file "$ACTIVE")" = "$trial_hash" ] && { resume_loaded=1; break; }
    [ ! -f "$D/stop.requested" ] || break
    sleep 1
  done
  if [ -f "$D/stop.requested" ]; then phase restoring; control_unlock; return 0; fi
  [ "$resume_loaded" = 1 ] || { control_unlock; return 1; }
  upnow; echo "0 $now" > "$D/cooling.new"; mv "$D/cooling.new" "$D/cooling"
  resume_count=0; [ ! -f "$D/resume.count" ] || read -r resume_count < "$D/resume.count"
  number "$resume_count" || resume_count=0
  echo "$((resume_count+1))" > "$D/resume.count"
  echo NONE > "$D/reason"; phase active; event "THERMAL_RESUMED cpu=$hot battery=$bat body=$body"
  control_unlock; emit_notice RESUMED
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
  role=$1
  peer_identity "$role" || return 0
  [ "$peer" != "$$" ] || return 0
  kill -TERM "$peer" 2>/dev/null
  for stop_tick in $(seq 1 20); do
    peer_identity "$role" || return 0
    sleep .1
  done
  kill -KILL "$peer" 2>/dev/null
}
restore() {
  why=$1
  echo "$why" > "$D/stop.requested"
  [ "$(cat "$D/phase" 2>/dev/null)" = restored ] && return 0
  if ! mkdir "$D/restore.lock" 2>/dev/null; then
    owner=''; owner_birth=''; read -r owner owner_birth < "$D/restore.lock/pid" 2>/dev/null
    if number "$owner" && kill -0 "$owner" 2>/dev/null && [ "$(birth "$owner")" = "$owner_birth" ]; then
      for retry in $(seq 1 15); do
        [ "$(cat "$D/phase" 2>/dev/null)" = restored ] && return 0
        sleep 1
      done
      return 1
    fi
    rm -f "$D/restore.lock/pid"; rmdir "$D/restore.lock" 2>/dev/null
    mkdir "$D/restore.lock" || return 1
  fi
  echo "$$ $(birth $$)" > "$D/restore.lock/pid"
  phase restoring
  # Finish any in-flight frequency write before releasing our votes.
  control_lock 150 || { phase recovery_failed; return 1; }
  release_gpu_vote; release_result=$?
  control_unlock
  # An OTA must never cause an old encoded profile to be installed into new
  # firmware. Retire only our own override and let the new factory daemon load.
  if [ "$(hash_file /vendor/bin/mi_thermald)" != "$BINARY" ] || [ "$(hash_file /odm/etc/thermal-normal.conf)" != "$ORIGINAL_CIPHER" ]; then
    existing=$(hash_file "$OVERRIDE")
    if [ "$existing" = "$trial_cipher_hash" ] || [ "$existing" = "$ORIGINAL_CIPHER" ]; then
      mv "$OVERRIDE" "$D/retired-after-update.bin" || return 1
      setprop ctl.restart mi_thermald
    fi
    [ "$RUNTIME_MOUNT" = 0 ] || unmount_runtime || return 1
    release_cpu_votes || release_result=1
    [ "$release_result" = 0 ] || { phase recovery_failed; return 1; }
    upnow; echo "$now" > "$D/stop.uptime"
    echo FIRMWARE_CHANGED > "$D/reason"; phase restored; event 'FIRMWARE_CHANGED: old profile retired'
    rm -f "$HOOK" "$D/restore.lock/pid"; rmdir "$D/restore.lock" 2>/dev/null
    stop_peer watch; stop_peer sentry
    return 0
  fi
  result=$release_result
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
  release_cpu_votes || result=1
  if [ "$RUNTIME_MOUNT" = 1 ]; then
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
    case "$why" in BATTERY_LIMIT|CPU_LIMIT) emit_notice STOPPED;; esac
  else phase recovery_failed; echo RECOVERY_FAILED > "$D/reason"; event 'RECOVERY_FAILED'; fi
  rm -f "$D/restore.lock/pid"; rmdir "$D/restore.lock" 2>/dev/null
  return "$result"
}
snapshot() (
  state=$(cat "$D/phase" 2>/dev/null); [ -n "$state" ] || state=idle
  recorded_state=$state
  started=0; read -r started < "$D/start.uptime" 2>/dev/null
  upnow
  watch_alive=0; sentry_alive=0
  peer_healthy watch && watch_alive=1
  peer_healthy sentry && sentry_alive=1
  if guard_phase "$state" && ! starting_grace && { [ "$watch_alive" != 1 ] || [ "$sentry_alive" != 1 ]; }; then state=unprotected; fi
  [ "$state" != restored ] || read -r now < "$D/stop.uptime" 2>/dev/null
  elapsed=$((now-started)); [ "$elapsed" -ge 0 ] || elapsed=0
  remaining=-1; [ "$duration" = 0 ] || remaining=$((duration-elapsed))
  echo "state=$state"; echo "session=$ID"; echo "mode=$mode"
  echo "recorded_state=$recorded_state"; echo "watch_alive=$watch_alive"; echo "sentry_alive=$sentry_alive"
  echo "watch_heartbeat=$(cat "$D/watch.beat" 2>/dev/null)"; echo "sentry_heartbeat=$(cat "$D/sentry.beat" 2>/dev/null)"
  echo "boost_percent=${boost_percent:-25}"
  echo "elapsed=$elapsed"; echo "remaining=$remaining"; echo "resume=$resume_boot"
  echo "cpu_mc=$hot"; echo "battery_dc=$bat"; echo "body_mc=$body"
  echo "freq0=$freq0"; echo "freq6=$freq6"; echo "max0=$max0"; echo "max6=$max6"
  echo "freq3=$freq3"; echo "max3=$max3"; echo "scene=$scene"; echo "balance=$balance"
  if [ "$RUNTIME_MOUNT" = 1 ]; then
    echo "gpu_enabled=$gpu_enabled"; echo "gpu_cap_hz=$gpu_cap_hz"; echo "gpu_hz=$gpu_hz"; echo "gpu_max_hz=$gpu_max_hz"; echo "gpu_thermal_level=$gpu_thermal_level"
  fi
  echo "battery_limit=$battery_limit"; echo "body_limit=$body_limit"; echo "cpu_limit=$cpu_limit"
  load_cooling
  echo "body_mode=$body_mode"; echo "body_resume=$body_resume"; echo "cooling_level=$cooling_level"; echo "cooling_percent=$((100-cooling_level*20))"
  notice_seq=0; notice_kind=''; [ ! -f "$D/notice" ] || read -r notice_seq notice_kind < "$D/notice"
  echo "thermal_auto_resume=$thermal_auto_resume"; echo "battery_resume=$battery_resume"; echo "cpu_resume=$cpu_resume"
  echo "notice_seq=$notice_seq"; echo "notice_kind=$notice_kind"; echo "resume_count=$(cat "$D/resume.count" 2>/dev/null)"
  if [ "$state" = unprotected ]; then echo reason=GUARD_UNHEALTHY; else echo "reason=$(cat "$D/reason" 2>/dev/null)"; fi
  echo "current_ua=$current"; echo "voltage_uv=$voltage"
)
command=$1
case "$command" in
  status|reconcile)
    discover; readings
    if [ -f "$B/current" ]; then
      read -r id < "$B/current"; select_session "$id"
      if [ "$RUNTIME_MOUNT" = 1 ] && [ "$(cat "$D/boot.id" 2>/dev/null)" != "$(cat /proc/sys/kernel/random/boot_id)" ]; then
        [ -n "$(mount_id)" ] || { echo REBOOT_RECOVERY > "$D/reason"; phase restored; }
      fi
      # UI polling may repair an orphan, but never silently restart a dead
      # experiment. A read-only status still reports the actual unhealthy state.
      state=$(cat "$D/phase" 2>/dev/null); upnow
      if [ "$command" = reconcile ] && guard_phase "$state" && ! starting_grace && { ! peer_healthy watch || ! peer_healthy sentry; }; then
        # /proc/uptime includes suspend. Let living guards refresh after a
        # system-wide scheduling gap; dead/reused PIDs need no grace period.
        if peer_identity watch && peer_identity sentry; then sleep 3; upnow; fi
        if ! peer_healthy watch || ! peer_healthy sentry; then restore GUARD_UNHEALTHY || exit 1; readings; fi
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
    if [ -f "$D/telemetry.1.csv" ]; then cat "$D/telemetry.1.csv"; sed '1d' "$D/telemetry.csv"; elif [ -f "$D/telemetry.csv" ]; then cat "$D/telemetry.csv"; fi
    exit 0;;
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
    detach_guard || error GUARD_DETACH_FAILED
    read -r start < "$D/start.uptime"
    while :; do
      state=$(cat "$D/phase" 2>/dev/null); [ "$state" != restored ] || exit 0
      heartbeat
      if [ "$((now-start))" -gt 5 ] && [ "$loop_gap" -le 30 ]; then
        if ! peer_healthy watch; then
          restore WATCHDOG_LOST; exit
        fi
      fi
      if [ "$duration" != 0 ] && [ "$((now-start))" -ge "$((duration+10))" ]; then restore DEADLINE_FALLBACK; exit; fi
      sleep 2
    done;;
  watch)
    detach_guard || error GUARD_DETACH_FAILED
    trap 'restore WATCHDOG_EXIT' EXIT
    trap 'exit 130' HUP INT TERM
    discover
    for tick in $(seq 1 25); do heartbeat; [ -f "$D/ready" ] && break; sleep 1; done
    [ -f "$D/ready" ] || { restore ACTIVATION_TIMEOUT; exit 1; }
    read -r start < "$D/start.uptime"; tick=0; cool_ready_since=0
    csv_header='uptime_s,elapsed_s,cpu_hottest_mc,battery_dc,body_mc,current0_khz,current6_khz,max0_khz,max6_khz,current_ua,voltage_uv,current3_khz,max3_khz,scene,state'
    [ "$RUNTIME_MOUNT" = 0 ] || csv_header="$csv_header,gpu_hz,gpu_max_hz,gpu_thermal_level,gpu_enabled,cooling_level,cooling_percent"
    echo "$csv_header" > "$D/telemetry.csv"
    while :; do
      state=$(cat "$D/phase"); case "$state" in active|paused|thermal_paused) :;; *) break;; esac
      heartbeat
      if [ "$loop_gap" -le 30 ]; then peer_healthy sentry || { restore SENTRY_LOST; exit; }; fi
      within_limits; valid=$?
      csv_row="$now,$((now-start)),$hot,$bat,$body,$freq0,$freq6,$max0,$max6,$current,$voltage,$freq3,$max3,$scene,$state"
      load_cooling || { restore CONTROL_ERROR; exit; }
      [ "$RUNTIME_MOUNT" = 0 ] || csv_row="$csv_row,$gpu_hz,$gpu_max_hz,$gpu_thermal_level,$gpu_enabled,$cooling_level,$((100-cooling_level*20))"
      echo "$csv_row" >> "$D/telemetry.csv"
      snapshot > "$D/snapshot.new"; mv "$D/snapshot.new" "$D/snapshot"
      if [ "$((tick%120))" = 119 ] && [ "$(wc -c < "$D/telemetry.csv")" -gt 524288 ]; then
        mv "$D/telemetry.csv" "$D/telemetry.1.csv"
        echo "$csv_header" > "$D/telemetry.csv"
      fi
      if [ "$state" != thermal_paused ] && [ "$valid" != 0 ]; then
        event "LIMIT cpu=$hot battery=$bat body=$body"
        if [ "$thermal_auto_resume" = 1 ] && { [ "$reason" = BATTERY_LIMIT ] || [ "$reason" = CPU_LIMIT ]; }; then
          pause_for_temperature "$reason" || { restore CONTROL_ERROR; exit; }
          cool_ready_since=0; tick=$((tick+1)); sleep 1; continue
        fi
        restore "$reason"; exit
      fi
      [ "$valid" = 0 ] || [ "$reason" != SENSOR_ERROR ] || { restore SENSOR_ERROR; exit; }
      [ "$duration" = 0 ] || [ "$((now-start))" -lt "$duration" ] || { restore TIMER; exit; }
      if [ "$((tick%15))" = 0 ]; then
        cmd package path dev.pulse.monitor 2>/dev/null | grep -q '^package:' || { restore APP_REMOVED; exit; }
      fi
      if [ "$state" = thermal_paused ]; then
        [ "$(getprop init.svc.mi_thermald)" = running ] && [ "$(getprop init.svc.vendor.thermal-hal)" = running ] || { restore THERMAL_SERVICE_STOPPED; exit; }
        [ "$(hash_file "$OVERRIDE")" = "$ORIGINAL_CIPHER" ] || { restore CONFIG_CHANGED; exit; }
        if temperature_resume_ready && [ "$(hash_file "$ACTIVE")" = "$ORIGINAL" ]; then
          [ "$cool_ready_since" != 0 ] || cool_ready_since=$now
          if [ "$((now-cool_ready_since))" -ge 5 ]; then resume_after_cooling || { restore CONTROL_ERROR; exit; }; cool_ready_since=0; fi
        else cool_ready_since=0; fi
        tick=$((tick+1)); sleep 1; continue
      fi
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
      update_cooling || { restore CONTROL_ERROR; exit; }
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
if [ "$gpu_enabled" = 1 ]; then
  gpu_table='1450000000 1400000000 1350000000 1275000000 1201000000 1126000000 1025000000 951000000 843000000 801000000 759000000 693000000 636000000 578000000 539000000 500000000 461000000 422000000 390000000 357000000 325000000 274000000 222000000 190000000 157000000 125000000'
  actual_table=$(cat "$GPU/gpu_available_frequencies" | xargs)
  [ "$actual_table" = "$gpu_table" ] || error GPU_TABLE_CHANGED
  selected=$(echo "$gpu_table" | cut -d ' ' -f "$((gpu_level+1))")
  [ "$selected" = "$gpu_cap_hz" ] || error INVALID_GPU_POLICY
fi
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
  case "$old_state" in active|paused|thermal_paused|thermal_pausing|starting|restoring|recovery_failed) error SESSION_ALREADY_ACTIVE;; esac
fi
[ ! -f "$D/ready" ] || [ "$3" = resume ] || error SESSION_ALREADY_USED
rm -f "$D/ready" "$D/stop.requested" "$D/notice" "$D/resume.count" "$D/watch.pid" "$D/sentry.pid" "$D/watch.birth" "$D/sentry.birth" "$D/watch.beat" "$D/sentry.beat"
rm -f "$D/stop.uptime"
read -r initial_balance < "$TM/balance_mode"
echo "initial_balance=$initial_balance" > "$D/runtime.env"
if body_feedback && [ "$gpu_enabled" = 1 ]; then
  gpu_user_read || error GPU_USER_LIMIT_UNAVAILABLE
  initial_gpu_user_level=$gpu_user_level
  echo "initial_gpu_user_level=$initial_gpu_user_level" >> "$D/runtime.env"
fi
echo '0 0' > "$D/cooling"
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
nohup setsid /system/bin/sh "$SELF" watch "$ID" > "$D/watch.log" 2>&1 </dev/null &
nohup setsid /system/bin/sh "$SELF" sentry "$ID" > "$D/sentry.log" 2>&1 </dev/null &
guards_ready=0
for guard_tick in $(seq 1 5); do
  upnow
  if peer_healthy watch && peer_healthy sentry; then guards_ready=1; break; fi
  sleep 1
done
[ "$guards_ready" = 1 ] || error GUARD_START_FAILED
mount_runtime || error RUNTIME_MOUNT_FAILED
within_limits || error "PREFLIGHT_$reason"
if body_feedback && [ "$gpu_enabled" = 1 ]; then
  # Restarting mi_thermald can reset max_pwrlevel between the initial capture
  # and our first write. Retain the stricter of both readings; never use this
  # transition to lift a pre-existing GPU user cap.
  gpu_user_read || error GPU_USER_LIMIT_UNAVAILABLE
  if [ "$gpu_user_level" != "$initial_gpu_user_level" ]; then
    [ "$initial_gpu_user_level" -ge "$gpu_user_level" ] || initial_gpu_user_level=$gpu_user_level
    echo "initial_gpu_user_level=$initial_gpu_user_level" >> "$D/runtime.env"
    echo "$gpu_user_level" >> "$D/gpu.votes"
    event "GPU_RESTART_BASELINE current=$gpu_user_level retained=$initial_gpu_user_level"
  fi
fi
if body_feedback; then update_cooling initial || error BODY_CONTROL_FAILED; fi
install "$D/trial.bin" || error INSTALL_FAILED
loaded=0
for tick in $(seq 1 6); do [ "$(hash_file "$ACTIVE")" = "$trial_hash" ] && { loaded=1; break; }; sleep 1; done
[ "$loaded" = 1 ] || error VERIFY_FAILED
if ! body_feedback; then
  echo "$cap0" > "$P0/scaling_max_freq"
  echo "$cap6" > "$P6/scaling_max_freq"
  [ "$RUNTIME_MOUNT" = 0 ] || echo "$cap3" > "$P3/scaling_max_freq"
fi
phase active; event "ACTIVE mode=$mode duration=$duration resume=$resume_boot gpu=$gpu_enabled gpu_cap_hz=$gpu_cap_hz"
echo ready > "$D/ready"
echo "OK ACTIVE $ID"
