#!/usr/bin/env python3
"""Execute the shipped controller functions against fake files (never real sysfs).
Runs on host dash and, optionally, the connected Android's actual /system/bin/sh.
"""
import argparse, pathlib, subprocess, tempfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
PREFIX = (ROOT/'app/src/main/assets/thermal/runner.sh').read_text().split('\ncommand=$1\n')[0]
TESTS = r'''
set -e
T=$(mktemp -d "${PULSE_TEST_TMP:-/tmp}/pulse-control.XXXXXX")
trap 'rm -rf "$T"' EXIT
D=$T/session; GPU=$T/gpu; P0=$T/p0; P3=$T/p3; P6=$T/p6
mkdir -p "$D" "$GPU" "$P0" "$P3" "$P6"
GROUPS='0 3 6'; body_mode=regulate; body_limit=52000; body_resume=49000
cap0=1000000; cap3=1000000; cap6=1000000; gpu_enabled=1; gpu_cap_hz=1450000000; initial_gpu_user_level=0
for node in "$P0" "$P3" "$P6"; do
  echo '200000 400000 600000 800000 1000000' > "$node/scaling_available_frequencies"
  : > "$node/scaling_boost_frequencies"; echo 1000000 > "$node/scaling_max_freq"
done
echo '1450000000 1400000000 1350000000 1275000000 1201000000 1126000000 1025000000 951000000 843000000 801000000 759000000 693000000 636000000 578000000 539000000 500000000 461000000 422000000 390000000 357000000 325000000 274000000 222000000 190000000 157000000 125000000' > "$GPU/gpu_available_frequencies"
echo 0 > "$GPU/max_pwrlevel"; echo active > "$D/phase"; echo '0 0' > "$D/cooling"
assert_eq() { [ "$1" = "$2" ] || { echo "FAIL $3: actual=$1 expected=$2"; exit 1; }; }
step() { body=$1; now=$2; update_cooling; load_cooling; assert_eq "$cooling_level" "$3" "body=$body now=$now"; assert_eq "$(cat "$D/phase")" active 'session retained'; }
step 51999 100 0
step 52000 101 1
assert_eq "$(cat "$P0/scaling_max_freq")" 800000 cpu_throttle
assert_eq "$(cat "$GPU/max_pwrlevel")" 5 gpu_throttle
step 52000 105 1
step 52000 106 2
step 51000 200 2
step 49001 300 2
step 49000 301 1
step 48999 305 1
step 49000 306 0
assert_eq "$(cat "$P6/scaling_max_freq")" 1000000 cpu_recovery
assert_eq "$(cat "$GPU/max_pwrlevel")" 0 gpu_recovery
step 55000 307 4
assert_eq "$(cat "$GPU/max_pwrlevel")" 21 rapid_hot_escalation
step 60000 500 4
# Driver reset while hot: reassert the current cap without resetting escalation time.
saved_since=$cooling_since
echo 0 > "$GPU/max_pwrlevel"; echo 1000000 > "$P0/scaling_max_freq"
step 60000 500 4
assert_eq "$(cat "$GPU/max_pwrlevel")" 21 gpu_drift_repaired
assert_eq "$(cat "$P0/scaling_max_freq")" 200000 cpu_drift_repaired
assert_eq "$cooling_since" "$saved_since" drift_keeps_transition_time
step 49000 501 3
step 49000 506 2
step 49000 511 1
step 52000 512 1
step 52000 516 2
step 48000 521 1
step 48000 526 0
# Current performance mode: one below-limit sample returns directly to 100%.
body_mode=performance
step 51999 527 0
step 52000 528 1
step 51999 529 0
step 55000 530 4
step 51999 531 0
step 52000 532 1
step 52000 536 1
step 52000 537 2
step 51000 538 0
assert_eq "$(cat "$P0/scaling_max_freq")" 1000000 performance_cpu_full
assert_eq "$(cat "$GPU/max_pwrlevel")" 0 performance_gpu_full
body_mode=regulate
# Pause releases only Pulse's temporary soft cap; scene is kept paused.
step 54000 530 3
phase paused; now=531; update_cooling; load_cooling
assert_eq "$cooling_level" 0 pause_release
assert_eq "$(cat "$GPU/max_pwrlevel")" 0 pause_gpu_release
phase active
# Existing user cap must never be raised; cold return reinstates that cap.
initial_gpu_user_level=13; echo 13 > "$GPU/max_pwrlevel"; rm -f "$D/gpu.votes"
step 52000 540 1
assert_eq "$(cat "$GPU/max_pwrlevel")" 13 preserve_stricter_prior_gpu_cap
step 48000 545 0
assert_eq "$(cat "$GPU/max_pwrlevel")" 13 restore_prior_gpu_cap
initial_gpu_user_level=0; echo 0 > "$GPU/max_pwrlevel"; rm -f "$D/gpu.votes"
# Crash after sysfs write but before state save: write-ahead set still restores.
next_level=4; write_cooling_votes; release_gpu_vote
assert_eq "$(cat "$GPU/max_pwrlevel")" 0 crash_recovery
# A foreign user vote is neither overwritten on recovery nor accepted for control.
echo 17 > "$GPU/max_pwrlevel"; release_gpu_vote
assert_eq "$(cat "$GPU/max_pwrlevel")" 17 foreign_vote_preserved
if write_cooling_votes; then echo 'FAIL accepted foreign GPU vote'; exit 1; fi
# GPU-off policy touches only CPU.
gpu_enabled=0; next_level=2; write_cooling_votes
assert_eq "$(cat "$GPU/max_pwrlevel")" 17 gpu_off_preserved
release_cpu_votes
assert_eq "$(cat "$P0/scaling_max_freq")" 1000000 cpu_vote_release
assert_eq "$(cat "$P3/scaling_max_freq")" 1000000 cpu3_vote_release
assert_eq "$(cat "$P6/scaling_max_freq")" 1000000 cpu6_vote_release
# Execute real independent guard conditions, replacing sensor I/O only.
readings() { return 0; }
body_mode=performance
bat=400; hot=80000; body=60000; battery_limit=500; cpu_limit=105000
within_limits
bat=500
if within_limits; then echo 'FAIL battery hard stop'; exit 1; fi
assert_eq "$reason" BATTERY_LIMIT battery_guard
bat=400; hot=105000
if within_limits; then echo 'FAIL CPU hard stop'; exit 1; fi
assert_eq "$reason" CPU_LIMIT cpu_guard
hot=80000; body_mode=stop; body_limit=50000; body=50000
if within_limits; then echo 'FAIL legacy body hard stop'; exit 1; fi
assert_eq "$reason" BODY_LIMIT legacy_guard
# Reclaim a dead controller lock (the second guardian's crash path).
echo 999999999 > "$D/control.lock"; control_lock; control_unlock
# Frequency flooring handles unordered tables and clamps at hardware minimum.
floor_frequency 125 400 100 200; assert_eq "$selected_frequency" 100 floor
floor_frequency 10 400 100 200; assert_eq "$selected_frequency" 100 minimum

# Resume gating uses distinct CPU, battery and body readings.
body_mode=performance; body_limit=52000; cpu_limit=105000; cpu_resume=75000; battery_resume=420
hot=75000; bat=420; body=51999; temperature_resume_ready
hot=75001; if temperature_resume_ready; then echo 'FAIL early CPU resume'; exit 1; fi
hot=75000; bat=421; if temperature_resume_ready; then echo 'FAIL early battery resume'; exit 1; fi
bat=420; body=52000; if temperature_resume_ready; then echo 'FAIL early body resume'; exit 1; fi
body=51999
# Exercise actual pause/resume/ownership functions with fake vendor I/O.
ACTIVE=$T/active; OVERRIDE=$T/override; TM=$T/tm; mkdir -p "$TM"; echo 0 > "$TM/sconfig"
ORIGINAL=original; ORIGINAL_CIPHER=original; BINARY=binary; trial_hash=trial; trial_cipher_hash=trial
printf original > "$D/original.bin"; printf trial > "$D/trial.bin"; cp "$D/trial.bin" "$OVERRIDE"; cp "$D/trial.bin" "$ACTIVE"
hash_file() { case "$1" in /vendor/bin/mi_thermald) echo binary;; /odm/etc/thermal-normal.conf) echo original;; *) cat "$1";; esac; }
install() { cp "$1" "$OVERRIDE" && cp "$1" "$ACTIVE"; }
emit_notice() { echo "$1" >> "$D/notices.test"; }
upnow() { now=$((now+1)); }
gpu_enabled=1; initial_gpu_user_level=0; echo 0 > "$GPU/max_pwrlevel"; phase active
pause_for_temperature CPU_LIMIT
assert_eq "$(cat "$D/phase")" thermal_paused protection_pause
assert_eq "$(cat "$ACTIVE")" original protection_profile
assert_eq "$(cat "$D/notices.test")" PAUSED pause_notice
hot=75001; resume_after_cooling
assert_eq "$(cat "$D/phase")" thermal_paused no_early_resume
hot=75000; printf game > "$ACTIVE"; resume_after_cooling
assert_eq "$(cat "$D/phase")" thermal_paused no_foreign_scene_resume
cp "$D/original.bin" "$ACTIVE"; resume_after_cooling
assert_eq "$(cat "$D/phase")" active automatic_resume
assert_eq "$(cat "$ACTIVE")" trial original_selected_curve_restored
assert_eq "$(cat "$D/resume.count")" 1 single_resume
assert_eq "$(tail -1 "$D/notices.test")" RESUMED resume_notice
pause_for_temperature BATTERY_LIMIT
echo MANUAL > "$D/stop.requested"; resume_after_cooling
assert_eq "$(cat "$D/phase")" thermal_paused manual_stop_prevents_resume
assert_eq "$(cat "$ACTIVE")" original manual_stop_preserves_factory
assert_eq "$(cat "$D/resume.count")" 1 no_second_resume
printf 'PASS: performance mode 52 boundary + immediate full release below 52, legacy 52/49 boundaries, hysteresis, escalation, recovery, CPU/GPU votes, pause, crash ownership, foreign votes, GPU-off, independent guards, legacy body stop, stale lock, 75/42/body recovery gates, pause/resume notices, scene ownership, manual-stop cancellation\n'
'''

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--adb'); ap.add_argument('--serial'); a=ap.parse_args()
    script='getprop() { echo hongkong; }\n'+PREFIX+'\n'+TESTS
    with tempfile.TemporaryDirectory() as tmp:
        path=pathlib.Path(tmp)/'body-checks.sh'; path.write_text(script)
        subprocess.run(['/bin/dash',str(path)],check=True)
        if a.adb:
            adb=[a.adb]+(['-s',a.serial] if a.serial else [])
            remote='/data/local/tmp/pulse-body-checks.sh'
            try:
                subprocess.run(adb+['push',str(path),remote],check=True,capture_output=True)
                subprocess.run(adb+['shell','PULSE_TEST_TMP=/data/local/tmp /system/bin/sh '+remote],check=True)
            finally: subprocess.run(adb+['shell','rm -f '+remote],check=True)
if __name__=='__main__': main()
