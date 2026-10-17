#!/usr/bin/env python3
"""Activate Pulse's local Binder helper as ADB shell; never requests Root."""
import argparse
import re
import shlex
import subprocess
import time

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',help='ADB device serial')
parser.add_argument('--stop',action='store_true',help='Stop helper and restore original perf_harden')
args=parser.parse_args()
adb=['adb']+(['-s',args.serial] if args.serial else [])
def shell(cmd):
    return subprocess.check_output(adb+['shell',cmd],text=True).strip()
package='dev.pulse.monitor'
identity=shell('pm list packages -U '+package)
m=re.search(r'^package:dev\.pulse\.monitor uid:(\d+)$',identity,re.M)
if not m: raise SystemExit('Install Pulse and open it once before activation.')
uid=int(m.group(1));directory=f'/data/local/tmp/pulse-adb-{uid}'
pidfile=directory+'/pid';statefile=directory+'/perf_harden'
try:
    pid=shell('cat '+pidfile+' 2>/dev/null')
    if pid.isdigit():
        cmdline=shell('cat /proc/'+pid+'/cmdline 2>/dev/null')
        if 'pulse-adb' in cmdline or 'dev.pulse.monitor.AdbBridgeMain' in cmdline:
            shell('kill -TERM '+pid)
except subprocess.CalledProcessError: pass
if args.stop:
    previous=shell('cat '+statefile+' 2>/dev/null || true')
    if previous in ['0','1']:shell('setprop security.perf_harden '+previous)
    shell('rm -f '+pidfile+' '+statefile)
    print('Pulse ADB helper stopped; original perf_harden restored.')
    raise SystemExit()
apk=shell('pm path '+package).splitlines()[0].removeprefix('package:')
if not apk.startswith('/data/app/'):raise SystemExit('Unexpected package path: '+apk)
shell('mkdir -p '+directory+'; chmod 700 '+directory)
shell('if [ ! -f '+statefile+' ]; then getprop security.perf_harden > '+statefile+'; fi; setprop security.perf_harden 0')
command='CLASSPATH='+shlex.quote(apk)+' exec app_process /system/bin --nice-name=pulse-adb dev.pulse.monitor.AdbBridgeMain '+str(uid)
launch='nohup sh -c '+shlex.quote(command)+' </dev/null > '+directory+'/bridge.log 2>&1 & echo $! > '+pidfile
shell(launch)
for _ in range(15):
    log=shell('cat '+directory+'/bridge.log 2>/dev/null')
    if 'Pulse ADB bridge ready' in log:
        print('Pulse ADB mode activated (shell UID 2000). Reboot or --stop disables it.')
        print('The app may use user-space PMU and shell-readable sensors; unavailable interfaces stay unavailable.')
        break
    time.sleep(1)
else:
    previous=shell('cat '+statefile+' 2>/dev/null || true')
    if previous in ['0','1']:shell('setprop security.perf_harden '+previous)
    raise SystemExit('Bridge did not connect. Open Pulse and retry. Diagnostics:\n'+log)
