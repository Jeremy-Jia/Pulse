package dev.pulse.monitor;

import android.net.Uri;
import android.os.*;
import java.lang.Process;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** Binder capability supplied by an explicitly ADB-started shell process. No network socket. */
final class AdbBridge {
    static final String DESCRIPTOR="dev.pulse.monitor.AdbBridge.v1";
    static final int HELLO=1, READ=2, START=3, POLL=4, SIGNAL=5;
    static final Uri URI=Uri.parse("content://"+BuildConfig.APPLICATION_ID+".adb");
    private static volatile IBinder remote;

    static void attach(IBinder binder) throws RemoteException {
        if(remote==binder && available())return;
        if(binder==null)throw new IllegalArgumentException("Missing bridge binder");
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {data.writeInterfaceToken(DESCRIPTOR);binder.transact(HELLO,data,reply,0);reply.readException();
            if(reply.readInt()!=2000)throw new SecurityException("Expected ADB shell UID");
        } finally {data.recycle();reply.recycle();}
        remote=binder;
        binder.linkToDeath(()->{if(remote==binder)remote=null;},0);
    }
    static boolean available(){IBinder b=remote;return b!=null&&b.isBinderAlive();}
    static String read(String command,long timeout) {
        Parcel d=Parcel.obtain(),r=Parcel.obtain();
        try {d.writeInterfaceToken(DESCRIPTOR);d.writeString(command);d.writeLong(timeout);call(READ,d,r);return r.readString();}
        catch(Exception e){return null;}finally{d.recycle();r.recycle();}
    }
    static Process start(String command)throws IOException {
        Parcel d=Parcel.obtain(),r=Parcel.obtain();
        try {d.writeInterfaceToken(DESCRIPTOR);d.writeString(command);call(START,d,r);
            int id=r.readInt();ParcelFileDescriptor fd=r.readTypedObject(ParcelFileDescriptor.CREATOR);
            if(fd==null)throw new IOException("Missing output pipe");return new RemoteProcess(id,fd);
        }catch(Exception e){throw new IOException("ADB 辅助进程已断开，请重新激活",e);}finally{d.recycle();r.recycle();}
    }
    static void signalPid(long pid){
        Parcel d=Parcel.obtain(),r=Parcel.obtain();
        try {d.writeInterfaceToken(DESCRIPTOR);d.writeInt(0);d.writeLong(pid);call(SIGNAL,d,r);}
        catch(Exception ignored){}finally{d.recycle();r.recycle();}
    }
    private static void call(int code,Parcel d,Parcel r)throws RemoteException {
        IBinder b=remote;if(b==null||!b.transact(code,d,r,0))throw new RemoteException("ADB disconnected");r.readException();
    }
    private static final class RemoteProcess extends Process {
        final int id;final InputStream output;
        RemoteProcess(int id,ParcelFileDescriptor fd){this.id=id;output=new ParcelFileDescriptor.AutoCloseInputStream(fd);}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public InputStream getInputStream(){return output;}
        public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
        public int exitValue(){
            Parcel d=Parcel.obtain(),r=Parcel.obtain();
            try {d.writeInterfaceToken(DESCRIPTOR);d.writeInt(id);call(POLL,d,r);int code=r.readInt();
                if(code==Integer.MIN_VALUE)throw new IllegalThreadStateException();return code;
            }catch(RemoteException e){return 125;}finally{d.recycle();r.recycle();}
        }
        public int waitFor()throws InterruptedException {for(;;){try{return exitValue();}catch(IllegalThreadStateException e){Thread.sleep(100);}}}
        public boolean waitFor(long n,TimeUnit unit)throws InterruptedException {
            long end=System.nanoTime()+unit.toNanos(n);do{try{exitValue();return true;}catch(IllegalThreadStateException e){Thread.sleep(25);}}while(System.nanoTime()<end);return false;
        }
        public void destroy(){
            Parcel d=Parcel.obtain(),r=Parcel.obtain();
            try {d.writeInterfaceToken(DESCRIPTOR);d.writeInt(id);d.writeLong(0);call(SIGNAL,d,r);}
            catch(Exception ignored){}finally{d.recycle();r.recycle();}
        }
    }
}
