"""Opt-in hardware tests: exact raw QoS restoration across crashes/policy drift.

Temporarily changes the selected CPU vote / policy ceiling. Restores both in
finally. Run on a cool, rooted device without concurrent frequency tuning.
"""
import argparse
import json
import pathlib
import shlex
import subprocess
import time

def main():
    p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--device');p.add_argument('--cpu',type=int,default=6)
    p.add_argument('--output',default='docs/verification/research-1.9.1/frequency-restoration.json');a=p.parse_args()
    adb=[a.adb]+(['-s',a.device] if a.device else [])
    def shell(c):
        return subprocess.check_output(adb+['shell','su -M -c '+shlex.quote(c)],text=True,encoding='utf-8',errors='replace',timeout=30).strip()
    root=pathlib.Path(__file__).resolve().parents[1];remote='/data/local/tmp/pulse-lab-191/libmldsa_neon.so'
    shell('mkdir -p /data/local/tmp/pulse-lab-191')
    subprocess.run(adb+['push',str(root/'app/src/main/jniLibs/arm64-v8a/libmldsa_neon.so'),remote],check=True,capture_output=True)
    shell('chmod 755 '+remote)
    node=f'/sys/devices/system/cpu/cpu{a.cpu}/cpufreq/';vote='/sys/kernel/msm_performance/parameters/cpu_max_freq'
    def raw():return int(dict(x.split(':') for x in shell('cat '+vote).split())[str(a.cpu)])
    def write_raw(v):shell(f'echo {a.cpu}:{v} > {vote}')
    original=raw();ceiling=int(shell('cat '+node+'scaling_max_freq'));minimum=int(shell('cat '+node+'scaling_min_freq'))
    assert int(shell('cat /sys/class/power_supply/battery/temp'))<450,'Cool device before testing'
    table=sorted(set(int(x) for x in shell('cat '+node+'scaling_available_frequencies').split() if minimum<=int(x)<=ceiling))
    cap=max(x for x in table if x<=ceiling*.55);foreign=min(x for x in table if x>cap);external=max(x for x in table if x<cap)
    rows=[];child=None;pid=None
    def spawn():
        nonlocal child,pid
        c='exec '+remote+f' --cpu {a.cpu} --signatures 8192 --cap {cap}'
        child=subprocess.Popen(adb+['shell','su -M -c '+shlex.quote(c)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
        pid=None
        for line in child.stdout:
            if not line.startswith('{'):continue
            e=json.loads(line)
            if e.get('event')=='started':pid=e['pid']
            if e.get('event')=='frequency_cap':break
        assert pid and raw()==cap,'Independent cap was not applied'
    try:
        for case in ('completed','TERM','KILL','previous_raw_vote','external_policy_change','foreign_raw_vote'):
            write_raw(foreign if case=='previous_raw_vote' else original)
            before=raw();spawn();extra={}
            if case=='TERM':
                contender=subprocess.run(adb+['shell','su -M -c '+shlex.quote(remote+f' --cpu {a.cpu} --signatures 8 --cap {cap}')],capture_output=True,timeout=15)
                assert contender.returncode!=0,'Concurrent owner was not excluded';extra['concurrent_owner_blocked']=True
            if case=='external_policy_change':shell(f'echo {external} > {node}scaling_max_freq')
            if case=='foreign_raw_vote':write_raw(foreign)
            if case!='completed':shell(f'kill -{ "TERM" if case=="TERM" else "KILL" } {pid}')
            out,_=child.communicate(timeout=20);child=None;pid=None
            deadline=time.monotonic()+4;expected=foreign if case=='foreign_raw_vote' else before
            while raw()!=expected and time.monotonic()<deadline:time.sleep(.05)
            after=raw();effective=int(shell('cat '+node+'scaling_max_freq'))
            assert after==expected,(case,after,expected)
            if case=='external_policy_change':assert effective==external,(effective,external)
            if case=='completed':
                result=next(json.loads(x) for x in out.splitlines() if x.startswith('{') and json.loads(x).get('event')=='result')
                assert result['valid'] and result['cap_restored'];extra['verified']=result['verified'];extra['telemetry']=result['telemetry']
            row=dict(case=case,before_raw=before,after_raw=after,requested_khz=cap,effective_after_khz=effective,**extra);rows.append(row);print(json.dumps(row),flush=True)
            shell(f'echo {ceiling} > {node}scaling_max_freq');write_raw(original)
    finally:
        if child is not None:
            if pid:shell(f'kill -TERM {pid} 2>/dev/null || true')
            child.communicate(timeout=20)
        shell(f'echo {ceiling} > {node}scaling_max_freq');write_raw(original)
    result={'result':'pass','original_raw':original,'final_raw':raw(),'final_effective_khz':int(shell('cat '+node+'scaling_max_freq')),'tests':rows}
    dest=root/a.output;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':main()
