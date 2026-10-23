"""Opt-in fault injection on a rooted hongkong test device.

Requires an already active user-approved unlimited session. Restarts that saved
policy after each recovery test. No stress workload and no temperature changes.
"""
import argparse,json,pathlib,shlex,subprocess,time

def main():
    p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--device',required=True);a=p.parse_args()
    adb=[a.adb,'-s',a.device];base='/data/adb/pulse-thermal';runner=f'/system/bin/sh {base}/runner.sh';rows=[]
    def shell(c,root=True):
        return subprocess.check_output(adb+['shell','su -M -c '+shlex.quote(c) if root else c],text=True,encoding='utf-8',errors='replace',timeout=55).strip()
    def status():return dict(line.split('=',1) for line in shell(runner+' status').splitlines() if '=' in line)
    def record(name,**fields):rows.append(dict(test=name,**fields));print(json.dumps(rows[-1]),flush=True)
    def wait_state(wanted):
        until=time.monotonic()+35
        while time.monotonic()<until:
            s=status()
            if s['state']==wanted:return s
            time.sleep(.5)
        raise AssertionError((wanted,s))
    def instrument(action):
        out=shell('am instrument -w -e guard true -e action '+action+' dev.pulse.monitor.test/dev.pulse.monitor.MonitorInstrumentation',False)
        assert 'INSTRUMENTATION_CODE: -1' in out,out
        value=json.loads(next(x.split('report_json=',1)[1] for x in out.splitlines() if 'report_json=' in x))
        # Instrumentation finish tears down the target app. Reopen its physical
        # display explicitly; otherwise HyperOS may enter its screen-off scene.
        shell('input keyevent KEYCODE_WAKEUP; am start --display 0 -n dev.pulse.monitor/dev.pulse.thermal.MainActivity')
        return value
    def stop_app():shell('am force-stop dev.pulse.monitor',False)
    def start():
        r=instrument('apply');s=status();assert s['state']=='active' and s['remaining']=='-1',(r,s)
        stop_app();return s
    def target(s):
        assert s['session'].isdigit();return base+'/sessions/'+s['session']
    def kill_roles(s,roles,signal='KILL'):
        d=target(s);pids=[]
        for role in roles:
            pid=shell(f'cat {d}/{role}.pid');birth=shell(f'cat {d}/{role}.birth')
            assert shell(f"awk '{{print $22}}' /proc/{pid}/stat")==birth,'PID reused'
            cmd=shell(f"tr '\\0' ' ' < /proc/{pid}/cmdline")
            assert f'{base}/runner.sh {role} {s["session"]}' in cmd,cmd
            pids.append(pid)
        shell('kill -'+signal+' '+' '.join(pids))
    def factory(s):
        assert s['state']=='restored',s
        assert shell("awk '$5==\"/data/vendor/thermal/config\" {print $1}' /proc/1/mountinfo")==''
        assert shell('getprop init.svc.mi_thermald')=='running'
        assert shell('sha256sum /data/vendor/thermal/decrypt.txt').split()[0]=='89b19321791814e190ffa93ac5accb34f45bf21e2e673a678d7857ef24e3d7f6'
    s=status();assert s['state']=='active' and s['remaining']=='-1',s
    # Exercise the actual shell functions with a deliberately stale caller clock.
    # Old peer_healthy incorrectly rejects a valid newer peer heartbeat.
    root=pathlib.Path(__file__).resolve().parents[1]
    source=(root/'app/src/main/assets/thermal/runner.sh').read_text(encoding='utf-8').split('command=$1\n',1)[0]
    source+='\nselect_session "$(cat "$B/current")"\nnow=0\npeer_healthy watch && peer_healthy sentry || exit 1\necho CLOCK_ORDER_OK\n'
    import tempfile
    with tempfile.TemporaryDirectory() as directory:
        f=pathlib.Path(directory)/'peer-clock.sh';f.write_text(source,encoding='utf-8',newline='\n')
        subprocess.run(adb+['push',str(f),'/data/local/tmp/pulse-peer-clock.sh'],check=True,capture_output=True)
    assert shell('/system/bin/sh /data/local/tmp/pulse-peer-clock.sh')=='CLOCK_ORDER_OK'
    record('heartbeat_clock_read_order',stale_caller_clock_accepted=True)
    d=target(s)
    for role in ('watch','sentry'):
        cg=shell(f'cat {d}/{role}.cgroup');assert all(x.endswith(':/') for x in cg.splitlines()),cg
    shell('input keyevent KEYCODE_HOME',False);stop_app();time.sleep(12)
    after=status();assert after['state']=='active' and after['session']==s['session']
    assert int(after['watch_heartbeat'])>int(s['watch_heartbeat']) and int(after['sentry_heartbeat'])>int(s['sentry_heartbeat'])
    record('force_stop_app',session_retained=True,heartbeats_advance=True,root_cgroups=True,max6=after['max6'])
    record('return_to_pulse',**instrument('inspect'))
    stop_app()
    for role,reason in (('watch','WATCHDOG_LOST'),('sentry','SENTRY_LOST')):
        s=status();kill_roles(s,[role]);restored=wait_state('restored');factory(restored)
        assert restored['reason']==reason,restored
        record('kill_'+role,reason=reason,factory_hash_verified=True)
        start()
    s=status();kill_roles(s,['watch','sentry']);time.sleep(.3)
    orphan=status();assert orphan['state']=='unprotected' and orphan['recorded_state']=='active',orphan
    record('both_guards_lost',state=orphan['state'],reason=orphan['reason'])
    # A live, unrelated process in the PID file must never be terminated.
    d=target(s);sentinel=shell('nohup sleep 90 >/dev/null 2>&1 </dev/null & echo $!')
    try:
        shell(f'echo {sentinel} > {d}/watch.pid')
        r=instrument('inspect');restored=status();factory(restored)
        assert restored['reason']=='GUARD_UNHEALTHY' and r['state']=='restored'
        shell(f'kill -0 {sentinel}');record('app_reconcile_and_pid_reuse',factory_hash_verified=True,unrelated_process_alive=True)
    finally:shell(f'kill {sentinel} 2>/dev/null || true')
    s=start();kill_roles(s,['watch','sentry'],'STOP');d=target(s)
    old=max(0,int(s['watch_heartbeat'])-40)
    shell(f'echo {old} > {d}/watch.beat; echo {old} > {d}/sentry.beat')
    kill_roles(s,['watch','sentry'],'CONT');time.sleep(4)
    resumed=status();assert resumed['state']=='active' and resumed['session']==s['session'],resumed
    record('system_scheduling_gap_resync',session_retained=True,heartbeats_fresh=True)
    kill_roles(s,['watch','sentry'],'STOP')
    old=max(0,int(s['watch_heartbeat'])-40)
    shell(f'echo {old} > {d}/watch.beat; echo {old} > {d}/sentry.beat')
    assert status()['state']=='unprotected'
    instrument('inspect');restored=status();factory(restored)
    record('stale_heartbeats',factory_hash_verified=True,reason=restored['reason'])
    final=start();record('final_saved_policy',**final)
    # Leave Pulse visible and its notification monitoring the existing session.
    record('final_ui',**instrument('inspect'))
    output=pathlib.Path(__file__).resolve().parents[1]/'docs/verification/research-1.9.1/thermal-guards.json'
    output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps({'result':'pass','tests':rows},indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':main()
