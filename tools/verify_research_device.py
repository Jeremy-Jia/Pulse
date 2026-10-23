"""Run the shipped native lab on a connected owner device; save raw, reproducible results.

Requires a built APK/native outputs. Optional --sweep lowers CPU ceilings temporarily.
No partition, thermal profile, or persistent setting is modified.
"""
import argparse
import json
import pathlib
import shlex
import statistics
import subprocess
import time

ROOT=pathlib.Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--adb',required=True)
    p.add_argument('--device')
    p.add_argument('--cpu',type=int,default=6)
    p.add_argument('--rounds',type=int,default=3)
    p.add_argument('--signatures',type=int,default=512)
    p.add_argument('--sweep',action='store_true')
    p.add_argument('--output',default='docs/verification/research-1.9.0/hardware.json')
    a=p.parse_args();adb=[a.adb]+(['-s',a.device] if a.device else [])
    def shell(command,root=False,timeout=160):
        if root:command='su -M -c '+shlex.quote(command)
        r=subprocess.run(adb+['shell',command],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
        if r.returncode:raise RuntimeError(r.stdout+r.stderr)
        return r.stdout.strip()
    dest='/data/local/tmp/pulse-lab-19'
    shell('mkdir -p '+dest)
    for f in ('libpulsememory.so',*[f'libmldsa_{b}.so' for b in ('scalar','neon','sve','sve2')]):
        subprocess.run(adb+['push',str(ROOT/'app/src/main/jniLibs/arm64-v8a'/f),dest+'/'+f],check=True,capture_output=True)
    shell('chmod 755 '+dest+'/*.so')
    report={'device':shell('getprop ro.product.device'),'kernel':shell('uname -r'),'capabilities':json.loads(shell(dest+'/libpulsememory.so --probe')),'runs':[]}
    output=ROOT/a.output;output.parent.mkdir(parents=True,exist_ok=True)
    def save():output.write_text(json.dumps(report,indent=2)+'\n')
    def run(binary,args,root=False):
        text=shell(' '.join(shlex.quote(s) for s in [dest+'/'+binary]+args),root=root)
        items=[json.loads(line) for line in text.splitlines() if line.startswith('{')]
        result=next(x for x in items if x.get('event')=='result')
        if not result.get('valid') and result.get('result')!='unsupported':raise RuntimeError(result)
        report['runs'].append(result);save();return result
    for b in ('scalar','neon','sve','sve2'):
        r=run(f'libmldsa_{b}.so',['--cpu',str(a.cpu),'--selftest'])
        print('KAT',b,r.get('kat_passed'),flush=True)
    for round in range(a.rounds):
        names=['scalar','neon','sve','sve2'];names=names[round%4:]+names[:round%4]
        if round%2:names.reverse()
        digest=None
        for b in names:
            r=run(f'libmldsa_{b}.so',['--cpu',str(a.cpu),'--signatures',str(a.signatures),'--round',str(round)],root=True)
            if r.get('result')=='unsupported':continue
            if digest is not None:assert digest==r['transcript_sha3shake256'], 'Cross-backend mismatch'
            digest=r['transcript_sha3shake256']
            print('CRYPTO',round,b,round_number(r['signatures_per_second']),round_number(r['telemetry']['sampled_mean_mhz']),flush=True)
            time.sleep(1.2)
    for threads in (1,4):
        for size in (256*1024,2*1024*1024,16*1024*1024,128*1024*1024):
            for mode in ('read','write','copy'):
                r=run('libpulsememory.so',['--cpu',str(a.cpu),'--threads',str(threads),'--bytes',str(size),'--seconds','.5','--mode',mode],root=True)
                print('MEM',threads,size,mode,r['payload_gbps'],flush=True)
                time.sleep(.3)
    if a.sweep:
        base=f'/sys/devices/system/cpu/cpu{a.cpu}/cpufreq/'
        original=int(shell('cat '+base+'scaling_max_freq',True))
        minimum=int(shell('cat '+base+'scaling_min_freq',True))
        table=sorted(set(int(x) for x in shell('cat '+base+'scaling_available_frequencies',True).split() if minimum<=int(x)<=original))
        chosen=sorted(set(max((x for x in table if x<=table[-1]*f),default=table[0]) for f in (.4,.55,.7,.85,1)))
        report['sweep_original_cap_khz']=original
        for cap in chosen:
            r=run('libmldsa_neon.so',['--cpu',str(a.cpu),'--signatures','2048','--round','99','--cap',str(cap)],root=True)
            if r.get('result')=='unsupported':
                print('SKIP',cap,'system policy changed',flush=True)
                continue
            assert r['cap_restored'] and r['telemetry']['cap_vote_after']==r['telemetry']['cap_vote_original']
            print('SWEEP',cap,round_number(r['signatures_per_second']),r['telemetry']['sampled_mean_mhz'],flush=True)
            time.sleep(2)
    medians={}
    for b in ('scalar','neon','sve','sve2'):
        samples=[x['signatures_per_second'] for x in report['runs'] if x.get('backend')==b and x.get('signatures')==a.signatures and x.get('round',99)<99]
        if samples:medians[b]=statistics.median(samples)
    report['crypto_medians_signatures_per_second']=medians
    report['speedup_vs_scalar']={b:v/medians['scalar'] for b,v in medians.items()}
    save();print(json.dumps({'medians':medians,'speedup':report['speedup_vs_scalar']},indent=2),flush=True)

def round_number(x):return round(x,2) if x is not None else None
if __name__=='__main__':main()
