package dev.pulse.monitor;

import android.os.*;
import java.lang.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Run only by tools/activate-adb.py using app_process as UID 2000. */
public final class AdbBridgeMain {
    private static final ConcurrentHashMap<Integer,Process> jobs=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer,Long> jobPids=new ConcurrentHashMap<>();
    private static final AtomicInteger ids=new AtomicInteger();
    public static void main(String[] args)throws Exception {
        System.setOut(new java.io.PrintStream(new FileOutputStream(FileDescriptor.out),true));
        if(android.os.Process.myUid()!=2000||args.length!=1)throw new SecurityException("Start via ADB shell");
        final int appUid=Integer.parseInt(args[0]);
        if(appUid<10000)throw new IllegalArgumentException("Invalid app UID");
        Looper.prepareMainLooper();
        Binder server=new Binder(){
            @Override protected boolean onTransact(int code,Parcel d,Parcel r,int flags)throws RemoteException {
                if(Binder.getCallingUid()!=appUid)throw new SecurityException("Wrong client UID");
                d.enforceInterface(AdbBridge.DESCRIPTOR);
                try {
                    if(code==AdbBridge.HELLO){r.writeNoException();r.writeInt(android.os.Process.myUid());}
                    else if(code==AdbBridge.READ){String cmd=d.readString();long timeout=Math.max(100,Math.min(8000,d.readLong()));
                        Process p=launch(cmd);ByteArrayOutputStream out=new ByteArrayOutputStream();
                        Thread drain=new Thread(()->copy(p.getInputStream(),out,128*1024));drain.setDaemon(true);drain.start();
                        boolean done=p.waitFor(timeout,TimeUnit.MILLISECONDS);if(!done)p.destroyForcibly();drain.join(500);
                        r.writeNoException();r.writeString(done?out.toString("UTF-8"):null);
                    } else if(code==AdbBridge.START){String cmd=d.readString();
                        if(jobs.values().stream().anyMatch(Process::isAlive))throw new IOException("A benchmark is already running");
                        jobs.clear();Process p=launch(cmd);int id=ids.incrementAndGet();jobs.put(id,p);
                        ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
                        Thread drain=new Thread(()->{try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){try(BufferedReader lines=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))) {
                            String line;int total=0;while((line=lines.readLine())!=null) {
                                if(line.startsWith("{\"event\":\"started\""))try {jobPids.put(id,new org.json.JSONObject(line).getLong("pid"));}catch(Exception ignored){}
                                byte[] bytes=(line+"\n").getBytes(StandardCharsets.UTF_8);total+=bytes.length;
                                if(total<=2*1024*1024){out.write(bytes);out.flush();}
                            }
                        }}catch(IOException ignored){p.destroy();}});
                        drain.setDaemon(true);drain.start();r.writeNoException();r.writeInt(id);r.writeTypedObject(pipe[0],0);pipe[0].close();
                    } else if(code==AdbBridge.POLL){Process p=jobs.get(d.readInt());int status=125;
                        if(p!=null){try{status=p.exitValue();}catch(IllegalThreadStateException e){status=Integer.MIN_VALUE;}}
                        r.writeNoException();r.writeInt(status);
                    } else if(code==AdbBridge.SIGNAL){int id=d.readInt();long pid=d.readLong();
                        if(pid>1){boolean known=jobPids.entrySet().stream().anyMatch(e->e.getValue()==pid&&jobs.get(e.getKey())!=null&&jobs.get(e.getKey()).isAlive());
                            if(known)android.os.Process.sendSignal((int)pid,15);
                        } else {Process p=jobs.get(id);if(p!=null)p.destroy();}r.writeNoException();
                    } else return false;
                    return true;
                }catch(Exception e){r.writeException(new IllegalStateException(e.toString()));return true;}
            }
        };
        Bundle extras=new Bundle();extras.putBinder("bridge",server);
        Bundle result=attachProvider(extras);
        if(result==null||!result.getBoolean("attached"))throw new IllegalStateException("Provider rejected attachment");
        IBinder client=result.getBinder("client");
        if(client==null)throw new IllegalStateException("Missing app lifetime binder");
        client.linkToDeath(()->{for(Process p:jobs.values())p.destroy();android.os.Process.killProcess(android.os.Process.myPid());},0);
        System.out.println("Pulse ADB bridge ready, uid="+appUid);
        Looper.loop();
    }
    private static Bundle attachProvider(Bundle extras)throws Exception {
        // Match Android's `content call`: an app_process shell has no AMS ApplicationThread.
        Class<?> manager=Class.forName("android.app.ActivityManager");
        java.lang.reflect.Method get=manager.getDeclaredMethod("getService");get.setAccessible(true);
        Object am=get.invoke(null);Class<?> api=Class.forName("android.app.IActivityManager");
        IBinder token=new Binder();String authority=AdbBridge.URI.getAuthority();
        Object holder=api.getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
            .invoke(am,authority,0,token,"pulse-adb");
        if(holder==null)throw new IOException("Pulse provider unavailable; open the app first");
        try {
            Object provider=holder.getClass().getField("provider").get(holder);
            Class<?> content=Class.forName("android.content.IContentProvider");
            for(java.lang.reflect.Method call:content.getMethods())if(call.getName().equals("call")) {
                Class<?>[] types=call.getParameterTypes();
                if(Build.VERSION.SDK_INT>=31&&types.length==5&&types[0].getName().equals("android.content.AttributionSource"))
                    return (Bundle)call.invoke(provider,new android.content.AttributionSource.Builder(2000).setPackageName("com.android.shell").build(),authority,"attach",null,extras);
            }
            for(java.lang.reflect.Method call:content.getMethods())if(call.getName().equals("call")) {
                Class<?>[] types=call.getParameterTypes();
                if(types.length==5&&types[0]==String.class)return (Bundle)call.invoke(provider,"com.android.shell",authority,"attach",null,extras);
            }
            for(java.lang.reflect.Method call:content.getMethods())if(call.getName().equals("call")&&call.getParameterTypes().length==4)
                return (Bundle)call.invoke(provider,"com.android.shell","attach",null,extras);
            throw new IOException("Unsupported provider call signature");
        } finally {
            try {api.getMethod("removeContentProviderExternalAsUser",String.class,IBinder.class,int.class).invoke(am,authority,token,0);}catch(Exception ignored){}
        }
    }
    private static Process launch(String cmd)throws IOException {
        if(cmd==null||cmd.length()>65536)throw new IOException("Invalid command length");
        Process p=new ProcessBuilder("/system/bin/sh","-c",cmd).redirectErrorStream(true).start();p.getOutputStream().close();return p;
    }
    private static void copy(InputStream in,OutputStream out,int limit){
        try{byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){if(total+n>limit)break;out.write(b,0,n);out.flush();total+=n;}}catch(IOException ignored){}
    }
}
