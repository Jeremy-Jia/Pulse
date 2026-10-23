"""Pinned ML-DSA reference, explicit arithmetic SIMD ports, and memory lab."""
import json
import re
import subprocess

def build(here, port, out, clang, readelf):
    ref = here / 'vendor/mldsa-ref'
    common = [str(clang), '--target=aarch64-linux-android26', '-O3', '-fwrapv', '-fno-lto',
              '-fPIE', '-std=gnu11', '-I'+str(ref), '-I'+str(here), '-DDILITHIUM_MODE=5',
              '-fno-vectorize', '-fno-slp-vectorize']
    objdump = clang.parent / ('llvm-objdump.exe' if str(clang).endswith('.exe') else 'llvm-objdump')
    reports = {}
    for backend, require, flags in [
        ('scalar', 0, ['-march=armv8-a', '-mgeneral-regs-only']),
        ('neon', 1, ['-march=armv8-a', '-DPULSE_NEON']),
        ('sve', 2, ['-march=armv8.2-a+sve', '-DPULSE_SVE']),
        ('sve2', 3, ['-march=armv8.5-a+sve2', '-DPULSE_SVE2'])]:
        target = port / ('mldsa-'+backend)
        target.mkdir(parents=True, exist_ok=True)
        objects=[]
        for name in ['sign', 'packing', 'polyvec', 'poly', 'ntt', 'reduce', 'rounding', 'symmetric-shake', 'fips202']:
            source=ref/(name+'.c')
            if backend != 'scalar' and name in ('poly','ntt'):
                text=source.read_text()
                text='#include "lab_vector.h"\n'+text
                if name=='poly':
                    text,n=re.subn(r'for\(i = 0; i < N; \+\+i\)\s+c->coeffs\[i\] = montgomery_reduce\(\(int64_t\)a->coeffs\[i\] \* b->coeffs\[i\]\);',
                        'pulse_pointwise(c->coeffs, a->coeffs, b->coeffs);',text)
                    if n!=1: raise RuntimeError('Pointwise source drift')
                else:
                    fwd='''for(j = start; j < start + len; ++j) {
        t = montgomery_reduce((int64_t)zeta * a[j + len]);
        a[j + len] = a[j] - t;
        a[j] = a[j] + t;
      }'''
                    fwd_vec='''j=start;
      if(len>=PV_LANES) for(;j+PV_LANES<=start+len;j+=PV_LANES){
        pv x=pv_load(a+j,PV_LANES),y=pv_mont(pv_dup(zeta),pv_load(a+j+len,PV_LANES));
        pv_store(a+j+len,pv_sub(x,y),PV_LANES);pv_store(a+j,pv_add(x,y),PV_LANES);
      }
      for(;j<start+len;j++){t=montgomery_reduce((int64_t)zeta*a[j+len]);a[j+len]=a[j]-t;a[j]+=t;}'''
                    inv='''for(j = start; j < start + len; ++j) {
        t = a[j];
        a[j] = t + a[j + len];
        a[j + len] = t - a[j + len];
        a[j + len] = montgomery_reduce((int64_t)zeta * a[j + len]);
      }'''
                    inv_vec='''j=start;
      if(len>=PV_LANES) for(;j+PV_LANES<=start+len;j+=PV_LANES){
        pv x=pv_load(a+j,PV_LANES),y=pv_load(a+j+len,PV_LANES);
        pv_store(a+j,pv_add(x,y),PV_LANES);pv_store(a+j+len,pv_mont(pv_dup(zeta),pv_sub(x,y)),PV_LANES);
      }
      for(;j<start+len;j++){t=a[j];a[j]=t+a[j+len];a[j+len]=montgomery_reduce((int64_t)zeta*(t-a[j+len]));}'''
                    final='''for(j = 0; j < N; ++j) {
    a[j] = montgomery_reduce((int64_t)f * a[j]);
  }'''
                    final_vec='''for(j=0;j<N;j+=PV_LANES){unsigned n=N-j;if(n>PV_LANES)n=PV_LANES;pv_store(a+j,pv_mont(pv_dup(f),pv_load(a+j,n)),n);}'''
                    for old,new in ((fwd,fwd_vec),(inv,inv_vec),(final,final_vec)):
                        if old not in text:raise RuntimeError('NTT source drift')
                        text=text.replace(old,new)
                source=target/(name+'.c');source.write_text(text, newline='\n')
            obj=target/(name+'.o')
            subprocess.run(common+flags+['-c',str(source),'-o',str(obj)],check=True)
            objects.append(obj)
        # Dispatch, timers, and JSON code are always baseline ARMv8-A, even for SVE variants.
        for name in ['lab_crypto','lab_common']:
            obj=target/(name+'.o')
            subprocess.run(common+['-march=armv8-a','-DPULSE_BACKEND="'+backend+'"','-DPULSE_REQUIRE='+str(require),'-c',str(here/(name+'.c')),'-o',str(obj)],check=True)
            objects.append(obj)
        destination=out/('libmldsa_'+backend+'.so')
        subprocess.run([str(clang),'--target=aarch64-linux-android26','-pie','-Wl,-z,max-page-size=16384','-Wl,-z,relro,-z,now']+[str(p) for p in objects]+['-lm','-o',str(destination)],check=True)
        disassembly=subprocess.check_output([str(objdump),'-d',str(target/'ntt.o'),str(target/'poly.o')],text=True)
        (target/'arithmetic-disassembly.txt').write_text(disassembly)
        patterns={'scalar':r'\bv[0-9]+\.', 'neon':r'\bv[0-9]+\.', 'sve':r'\bz[0-9]+\.', 'sve2':r'\b(smullb|smlslb|shrnb|shrnt)\b'}
        count=len(re.findall(patterns[backend],disassembly))
        if (backend=='scalar' and count) or (backend!='scalar' and not count):raise RuntimeError('ISA audit failed: '+backend)
        reports[backend]={'instruction_pattern_matches':count,'flags':flags}
        print('Built '+destination.name,flush=True)
    subprocess.run(common+['-march=armv8-a',str(here/'lab_memory.c'),str(here/'lab_common.c'),'-pie','-Wl,-z,max-page-size=16384','-lm','-o',str(out/'libpulsememory.so')],check=True)
    (port.parent/'lab-isa-audit.json').write_text(json.dumps(reports,indent=2))
