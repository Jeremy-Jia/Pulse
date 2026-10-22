#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>
static double now(void){ struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return t.tv_sec+t.tv_nsec/1e9; }
static GLuint shader(GLenum type,const char *src){ GLuint s=glCreateShader(type);glShaderSource(s,1,&src,0);glCompileShader(s);GLint ok;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);if(!ok){char e[2048];glGetShaderInfoLog(s,sizeof e,0,e);fprintf(stderr,"shader: %s\n",e);exit(3);}return s; }
int main(int argc,char **argv){
 int seconds=argc>1?atoi(argv[1]):12;if(seconds<1||seconds>30)return 2;
 EGLDisplay d=eglGetDisplay(EGL_DEFAULT_DISPLAY);if(!eglInitialize(d,0,0)){fprintf(stderr,"egl init %x\n",eglGetError());return 3;}
 const EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
 EGLConfig config;EGLint n;if(!eglChooseConfig(d,attrs,&config,1,&n)||n!=1)return 4;
 const EGLint pb[]={EGL_WIDTH,1024,EGL_HEIGHT,1024,EGL_NONE},ct[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
 EGLSurface surf=eglCreatePbufferSurface(d,config,pb);EGLContext ctx=eglCreateContext(d,config,EGL_NO_CONTEXT,ct);if(!eglMakeCurrent(d,surf,surf,ctx)){fprintf(stderr,"current %x\n",eglGetError());return 5;}
 const char *vs="#version 300 es\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0.,1.);}";
 const char *fs="#version 300 es\nprecision highp float;uniform float seed;out vec4 color;void main(){vec4 v=vec4(gl_FragCoord.xy/1024.,seed,0.17);for(int i=0;i<768;i++){v=fract(v.yzwx*vec4(1.231,1.317,1.419,1.513)+v*0.613+vec4(0.071,0.081,0.091,0.101));}color=v;}";
 GLuint p=glCreateProgram(),v=shader(GL_VERTEX_SHADER,vs),f=shader(GL_FRAGMENT_SHADER,fs);glAttachShader(p,v);glAttachShader(p,f);glLinkProgram(p);GLint ok;glGetProgramiv(p,GL_LINK_STATUS,&ok);if(!ok)return 6;glUseProgram(p);GLint loc=glGetUniformLocation(p,"seed");glViewport(0,0,1024,1024);glDisable(GL_DITHER);
 printf("renderer=%s\nversion=%s\n",glGetString(GL_RENDERER),glGetString(GL_VERSION));fflush(stdout);
 int frames=0;double t=now();while(now()-t<2){glUniform1f(loc,.13f+(frames++%100)*.001f);glDrawArrays(GL_TRIANGLES,0,3);glFinish();}
 frames=0;t=now();while(now()-t<seconds){glUniform1f(loc,.13f+(frames%100)*.001f);glDrawArrays(GL_TRIANGLES,0,3);glFinish();frames++;}
 double elapsed=now()-t;unsigned char pixels[4];glReadPixels(512,512,1,1,GL_RGBA,GL_UNSIGNED_BYTE,pixels);GLenum err=glGetError();printf("frames=%d\nseconds=%.6f\nfps=%.6f\npixel=%u,%u,%u,%u\ngl_error=%u\n",frames,elapsed,frames/elapsed,pixels[0],pixels[1],pixels[2],pixels[3],err);
 glDeleteProgram(p);glDeleteShader(v);glDeleteShader(f);eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(d,ctx);eglDestroySurface(d,surf);eglTerminate(d);return err?7:0;
}
