import dev.pulse.thermal.*;
import java.nio.file.*;
import java.util.*;
public final class Thermal18CodecTest {
    static void check(boolean ok,String msg) { if(!ok) throw new AssertionError(msg); }
    static void onlyCpu(byte[] before,byte[] after) {
        String section=""; int changed=0;
        String[] a=new String(before,java.nio.charset.StandardCharsets.UTF_8).split("\n",-1), b=new String(after,java.nio.charset.StandardCharsets.UTF_8).split("\n",-1);
        check(a.length==b.length,"line count");
        for(int i=0;i<a.length;i++) {
            if(a[i].startsWith("[")) section=a[i].trim();
            if(!a[i].equals(b[i])) { changed++; check(Set.of("[SS-CPU0]","[SS-CPU3]","[SS-CPU6]").contains(section)&&a[i].startsWith("target\t"),"non CPU field changed"); }
        }
        check(changed>=1&&changed<=3,"CPU target diff count");
    }
    public static void main(String[] args) throws Exception {
        Path p=Path.of(args[0]); byte[] key=Files.readAllBytes(Path.of(args[1]));
        byte[] binary=Files.readAllBytes(p.resolve("mi_thermald")), encrypted=Files.readAllBytes(p.resolve("original-encrypted.conf"));
        ConfigCodec c=new ConfigCodec(ThermalProfile.MI18,binary,encrypted,key); Arrays.fill(key,(byte)0);
        check(Arrays.equals(c.original(),Files.readAllBytes(p.resolve("active.conf"))),"original bytes");
        check(Arrays.equals(c.encode(c.original()),encrypted),"cipher roundtrip");
        for(int percent:new int[]{0,10,25,50,100}) onlyCpu(c.original(),c.make("boost",3744000,4032000,4704000,percent,null,null,null));
        byte[] full=c.make("fullmax",3744000,4032000,4704000,25,null,null,null); onlyCpu(c.original(),full);
        check(ConfigCodec.sha(full).equals("cac4b997daff5e716b6c2663264c028ff396563e8c88161a85138db16bc67444"),"phone-loaded fullmax bytes");
        for(int boost:new int[]{4857600,4953600,5011200}) onlyCpu(c.original(),c.make("fullmax",3744000,4032000,boost,25,null,null,null));
        check(ThermalProfile.MI18.maximum(6)==5011200,"Boost maximum is part of the selectable table");
        int[] a=ThermalProfile.MI18.curve(0), b=ThermalProfile.MI18.curve(3), d=ThermalProfile.MI18.curve(6); b[1]=4032000;
        onlyCpu(c.original(),c.make("custom",3744000,4032000,4704000,25,a,b,d));
        try { c.make("fullmax",3744000,4000000,4704000,25,null,null,null); throw new AssertionError("bad group3 frequency"); } catch(IllegalArgumentException expected) {}
        try { c.make("custom",3744000,4032000,4704000,25,a,new int[1],d); throw new AssertionError("bad group3 count"); } catch(IllegalArgumentException expected) {}
        System.out.println("PASS hongkong: exact original/fullmax bytes, three groups, all boost levels, custom group3, rejects invalid group3; all other protections unchanged");
    }
}
