package dev.pulse.thermal;

/** Only firmware actually read and round-trip verified on hardware is accepted. */
public final class ThermalProfile {
    public final String device, name, binarySha, plainSha, cipherSha;
    public final int[] groups;
    public final boolean runtimeMount;
    private ThermalProfile(String d,String n,String b,String p,String c,int[] g,boolean m) {
        device=d; name=n; binarySha=b; plainSha=p; cipherSha=c; groups=g; runtimeMount=m;
    }
    public static final ThermalProfile MI15=new ThermalProfile("dada","Xiaomi 15",ConfigCodec.BINARY_SHA,ConfigCodec.ORIGINAL_SHA,ConfigCodec.CIPHER_SHA,new int[]{0,6},false);
    public static final ThermalProfile MI18=new ThermalProfile("hongkong","Xiaomi 18 Pro",
        "292b6416c32d84e752a1f451ed008f43f24942951c1b77a6ae85819383a13814",
        "89b19321791814e190ffa93ac5accb34f45bf21e2e673a678d7857ef24e3d7f6",
        "32e541070eb50a82911c4c4bcdde8f665dc8ea70756467fc11b6d0736ffd561a",new int[]{0,3,6},true);
    public static ThermalProfile forDevice(String d) {
        if(d.equals("dada")) return MI15;
        if(d.equals("hongkong")) return MI18;
        throw new IllegalArgumentException("此设备尚未适配温控策略："+d);
    }
    public int[] table(int g) {
        int[] normal=normalTable(g), boost=boostTable(g), all=java.util.Arrays.copyOf(normal,normal.length+boost.length);
        System.arraycopy(boost,0,all,normal.length,boost.length); return all;
    }
    public int[] boostTable(int g) { return runtimeMount && g==6?new int[]{4857600,4953600,5011200}:new int[0]; }
    public int[] normalTable(int g) {
        if(!runtimeMount) return (g==0?ConfigCodec.TABLE0:ConfigCodec.TABLE6).clone();
        return (g==0?new int[]{288000,384000,518400,633600,768000,902400,998400,1094400,1190400,1305600,1420800,1536000,1670400,1804800,1939200,2073600,2188800,2284800,2380800,2553600,2611200,2726400,2841600,2956800,3072000,3264000,3379200,3494400,3628800,3744000}:
            g==3?new int[]{288000,384000,480000,576000,672000,768000,864000,960000,1075200,1190400,1305600,1440000,1574400,1708800,1824000,1939200,2054400,2169600,2265600,2361600,2457600,2611200,2707200,2822400,2937600,3052800,3168000,3283200,3398400,3513600,3667200,3782400,3897600,4032000}:
            new int[]{288000,441600,633600,787200,902400,1017600,1132800,1248000,1363200,1478400,1593600,1708800,1804800,1920000,2035200,2265600,2438400,2611200,2880000,3110400,3206400,3321600,3552000,3782400,4032000,4108800,4166400,4243200,4300800,4377600,4435200,4550400,4627200,4704000});
    }
    public int maximum(int g) { int[] t=table(g); return t[t.length-1]; }
    public int[] curve(int g) {
        if(!runtimeMount) return ConfigCodec.originalCurve(g==6);
        return g==0?new int[]{2726400,2380800,2284800,1670400,1420800,1305600,1190400}:
            g==3?new int[]{2707200,2361600,2265600,1574400,1440000,1305600,1190400}:
            new int[]{2611200,2438400,1804800,1478400,1248000,1132800,1248000,1017600};
    }
    public int[] triggers(int g) {
        if(!runtimeMount) return (g==0?ConfigCodec.TRIGGER0:ConfigCodec.TRIGGER6).clone();
        return g==6?new int[]{25000,34000,37000,39000,40000,43000,45000,47000}:new int[]{25000,37000,39000,41000,43000,45000,47000};
    }
    public String label(int g) { return g==6?"超大核 · CPU6–7":runtimeMount?(g==0?"性能组 1 · CPU0–2":"性能组 2 · CPU3–5"):"性能核 · CPU0–5"; }
}
