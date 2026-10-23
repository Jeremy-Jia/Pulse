"""Exercise the native ceiling owner with TERM and KILL on the connected test device."""
import argparse
import json
import pathlib
import shlex
import subprocess
import time

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--adb',required=True);parser.add_argument('--device');parser.add_argument('--cpu',type=int,default=6)
    parser.add_argument('--output',default='docs/verification/research-1.9.0/forced-restoration.json');args=parser.parse_args()
    adb=[args.adb]+(['-s',args.device] if args.device else [])
    def shell(command):
        return subprocess.check_output(adb+['shell','su -M -c '+shlex.quote(command)],text=True,encoding='utf-8',errors='replace').strip()
    root=pathlib.Path(__file__).resolve().parents[1];remote='/data/local/tmp/pulse-lab-19/libmldsa_neon.so'
    subprocess.run(adb+['push',str(root/'app/src/main/jniLibs/arm64-v8a/libmldsa_neon.so'),remote],check=True,capture_output=True)
    shell('chmod 755 '+remote);base=f'/sys/devices/system/cpu/cpu{args.cpu}/cpufreq/';rows=[]
    for signal in ('TERM','KILL'):
        original=int(shell('cat '+base+'scaling_max_freq'));minimum=int(shell('cat '+base+'scaling_min_freq'))
        table=[int(x) for x in shell('cat '+base+'scaling_available_frequencies').split()]
        eligible=[f for f in table if minimum<=f<=original*.6]
        if not eligible:raise RuntimeError('Current policy is too narrow for a meaningful restoration test')
        cap=max(eligible);command='exec '+remote+f' --cpu {args.cpu} --signatures 8192 --cap {cap}'
        child=subprocess.Popen(adb+['shell','su -M -c '+shlex.quote(command)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
        pid=None
        while child.poll() is None:
            line=child.stdout.readline()
            if line.startswith('{'):
                event=json.loads(line)
                if event.get('event')=='started':pid=event['pid'];break
        if not pid:raise RuntimeError('No native owner PID')
        deadline=time.monotonic()+4
        while int(shell('cat '+base+'scaling_max_freq'))!=cap and time.monotonic()<deadline:time.sleep(.03)
        applied=int(shell('cat '+base+'scaling_max_freq'))
        if applied!=cap:
            shell(f'kill -TERM {pid}');child.communicate(timeout=20);raise RuntimeError('Cap did not apply; owner was stopped')
        contender=subprocess.run(adb+['shell','su -M -c '+shlex.quote(remote+f' --cpu {args.cpu} --signatures 8 --cap {cap}')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=20)
        if contender.returncode==0:
            shell(f'kill -TERM {pid}');child.communicate(timeout=20);raise RuntimeError('Concurrent cap owner was not excluded')
        shell(f'kill -{signal} {pid}');child.communicate(timeout=20)
        deadline=time.monotonic()+4;restored=int(shell('cat '+base+'scaling_max_freq'))
        while restored!=original and time.monotonic()<deadline:time.sleep(.05);restored=int(shell('cat '+base+'scaling_max_freq'))
        row={'signal':signal,'original_khz':original,'applied_khz':applied,'restored_khz':restored,'concurrent_owner_blocked':contender.returncode!=0,'pass':restored==original};rows.append(row);print(json.dumps(row),flush=True)
        if restored!=original:raise RuntimeError('Restoration verification failed')
        time.sleep(.5)
    output=root/args.output;output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps({'result':'pass','tests':rows},indent=2)+'\n',encoding='utf-8')

if __name__=='__main__':main()
