package dev.pulse.monitor;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** Only shell/root may attach a bridge; the returned capability stays inside this app UID. */
public final class AdbBridgeProvider extends ContentProvider {
    private static final Binder lifetime=new Binder();
    public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        int uid=Binder.getCallingUid();
        if(uid!=2000&&uid!=0)throw new SecurityException("ADB shell activation required");
        if(!"attach".equals(method)||extras==null)throw new IllegalArgumentException("Unknown method");
        try {AdbBridge.attach(extras.getBinder("bridge"));Bundle b=new Bundle();b.putBoolean("attached",true);b.putBinder("client",lifetime);return b;}
        catch(RemoteException e){throw new IllegalStateException(e);}
    }
    public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    public String getType(Uri u){return null;}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
