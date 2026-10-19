import dev.pulse.thermal.ConfigCodec;
import java.nio.file.*;
import java.util.*;
public class ThermalCodecTest {
    static void check(boolean b,String m) { if(!b) throw new AssertionError(m); }
    public static void main(String[] args) throws Exception {
        Path base=Path.of(args[0]);
        byte[] binary=Files.readAllBytes(base.resolve("mi_thermald")), encrypted=Files.readAllBytes(base.resolve("original-encrypted.conf"));
        ConfigCodec codec=new ConfigCodec(binary,encrypted);
        check(Arrays.equals(codec.original(),Files.readAllBytes(base.resolve("original.conf"))),"device plaintext roundtrip");
        byte[] plus=codec.make("plus25",3532800,4320000), full=codec.make("fullmax",3532800,4320000);
        check(Arrays.equals(plus,Files.readAllBytes(base.resolve("trial.conf"))),"plus25 matches prior verified curve");
        check(Arrays.equals(full,Files.readAllBytes(base.resolve("fullmax-20261019d/trial.conf"))),"fullmax matches phone-tested curve");
        check(Arrays.equals(codec.encode(full),Files.readAllBytes(base.resolve("fullmax-20261019d/trial-encrypted.conf"))),"fullmax exact ciphertext");
        codec.make("fullmax",2745600,3513600);
        codec.make("plus25",2745600,3513600);
        for(int percent:new int[]{0,10,25,50,100}) codec.make("boost",3532800,4320000,percent,null,null);
        int[] a=ConfigCodec.originalCurve(false), b=ConfigCodec.originalCurve(true); a[1]=3532800;
        byte[] custom=codec.make("custom",3532800,4320000,25,a,b);
        check(!Arrays.equals(custom,codec.original()),"one-cluster custom curve changed");
        try { codec.make("custom",3532800,4320000,25,new int[]{1},b); throw new AssertionError("invalid custom curve accepted"); } catch(IllegalArgumentException expected) {}
        try { codec.make("boost",3532800,4320000,101,null,null); throw new AssertionError("invalid boost accepted"); } catch(IllegalArgumentException expected) {}
        try { codec.make("fullmax",4000000,4320000); throw new AssertionError("invalid frequency accepted"); } catch(IllegalArgumentException expected) {}
        binary[0]^=1;
        try { new ConfigCodec(binary,encrypted); throw new AssertionError("unknown firmware accepted"); } catch(IllegalArgumentException expected) {}
        System.out.println("PASS: original roundtrip, exact plus25/fullmax device fixtures, encryption, custom caps, invalid frequency and firmware rejection");
    }
}
