#define _GNU_SOURCE
#include <elf.h>
#include <dlfcn.h>
#include <link.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
/* Only loads a private copy of the exactly hash-verified firmware binary.
 * No daemon entry point or constructors are executed. Output is private. */
static uintptr_t base;
static const char *loaded;
static int find_base(struct dl_phdr_info *info, size_t n, void *p) {
    (void)n; (void)p;
    if (info->dlpi_name && strcmp(info->dlpi_name, loaded)==0) base=info->dlpi_addr;
    return 0;
}
int main(int argc,char **argv) {
    if(argc!=4) { fprintf(stderr,"usage: codec firmware private-copy private-key\n"); return 2; }
    FILE *in=fopen(argv[1],"rb"); if(!in) { perror("firmware"); return 3; }
    fseek(in,0,SEEK_END); long size=ftell(in); rewind(in);
    if(size!=876912) { fclose(in); fprintf(stderr,"firmware size mismatch\n"); return 4; }
    unsigned char *buf=malloc(size); if(!buf || fread(buf,1,size,in)!=(size_t)size) return 5; fclose(in);
    Elf64_Ehdr *e=(Elf64_Ehdr*)buf;
    if(memcmp(e->e_ident,ELFMAG,SELFMAG)||e->e_machine!=EM_AARCH64||e->e_type!=ET_DYN||e->e_phentsize!=sizeof(Elf64_Phdr)||e->e_phoff+e->e_phnum*sizeof(Elf64_Phdr)>(size_t)size) return 6;
    int patched=0;
    for(int i=0;i<e->e_phnum;i++) {
        Elf64_Phdr *p=(Elf64_Phdr*)(buf+e->e_phoff)+i;
        if(p->p_type!=PT_DYNAMIC) continue;
        if(p->p_offset+p->p_filesz>(size_t)size) return 7;
        Elf64_Dyn *d=(Elf64_Dyn*)(buf+p->p_offset);
        for(size_t j=0;j<p->p_filesz/sizeof(*d)&&d[j].d_tag!=DT_NULL;j++) {
            if(d[j].d_tag==DT_FLAGS_1) { d[j].d_un.d_val&=~DF_1_PIE; patched++; }
            if(d[j].d_tag==DT_INIT_ARRAYSZ) { d[j].d_un.d_val=0; patched++; }
            if(d[j].d_tag==DT_INIT) d[j].d_tag=DT_DEBUG;
        }
    }
    if(patched!=2) return 8;
    umask(077); FILE *out=fopen(argv[2],"wb"); if(!out) { perror("copy"); return 9; }
    if(fwrite(buf,1,size,out)!=(size_t)size || fclose(out)) return 10; free(buf);
    loaded=argv[2]; void *handle=dlopen(loaded,RTLD_NOW|RTLD_LOCAL);
    if(!handle) { fprintf(stderr,"load: %s\n",dlerror()); unlink(argv[2]); return 11; }
    dl_iterate_phdr(find_base,NULL);
    if(!base) return 12;
    unsigned char key[32]={0}; int (*decode)(unsigned char*,int)=(void*)(base+0xb81e4);
    int result=decode(key,32);
    if(result) { fprintf(stderr,"decode failed: %d\n",result); return 13; }
    out=fopen(argv[3],"wb"); if(!out) return 14;
    int ok=fwrite(key,1,16,out)==16 && fclose(out)==0;
    memset(key,0,sizeof(key)); dlclose(handle); unlink(argv[2]);
    return ok?0:15;
}
