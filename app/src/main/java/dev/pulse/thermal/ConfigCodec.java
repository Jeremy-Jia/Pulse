package dev.pulse.thermal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Strict adapter for verified firmware, with no Xiaomi binary, key or profile bundled. */
public final class ConfigCodec {
    public static final String BINARY_SHA = "d6ddd1c6fe212e79d59a587b41212eaebd8873079c0a27f8161c6a5f1ea76075";
    public static final String ORIGINAL_SHA = "4a0fa3e62b1106b4f30df355579e633f0426267e39c580dd27c0a44ce71f3e2d";
    public static final String CIPHER_SHA = "c121ffb850df822c8d28ec18fc6736a748d120affe610da07d302cf95b780641";
    public static final int[] TABLE0 = {384000,556800,748800,960000,1152000,1363200,1555200,1785600,1996800,2227200,2400000,2745600,2918400,3072000,3321600,3532800};
    public static final int[] TABLE6 = {1017600,1209600,1401600,1689600,1958400,2246400,2438400,2649600,2841600,3072000,3283200,3513600,3801600,4089600,4204800,4320000};
    public static final int[] TRIGGER0={25000,34000,37000,39000,41000,42000,43000,44000,45000,46000,47000,48000,49000,50000};
    public static final int[] TRIGGER6={25000,32000,34000,37000,39000,40000,41000,41500,42000,43000,44000,45000,46000,47000,48000,50000};
    public static int[] originalCurve(boolean prime) {
        return prime?new int[]{2841600,2841600,2649600,1958400,1689600,1401600,1401600,1401600,1401600,1401600,1401600,1401600,1401600,1017600,1017600,1017600}:
            new int[]{2745600,2745600,2400000,2227200,1785600,1785600,1785600,1785600,1363200,1363200,1152000,1152000,960000,960000};
    }
    private final byte[] key, iv, original, cipher;
    private final ThermalProfile profile;
    public ConfigCodec(byte[] binary, byte[] ciphertext) throws Exception {
        this(ThermalProfile.MI15,binary,ciphertext,null);
    }
    public ConfigCodec(ThermalProfile p,byte[] binary,byte[] ciphertext,byte[] recovered) throws Exception {
        profile=p;
        require(sha(binary).equals(p.binarySha), "温控程序版本不匹配，拒绝修改");
        require(sha(ciphertext).equals(p.cipherSha), "原厂配置版本不匹配，拒绝修改");
        require(!p.runtimeMount || (recovered!=null && recovered.length==16),"固件编码初始化失败");
        key = p.runtimeMount?recovered.clone():Arrays.copyOfRange(binary, 0xd0538, 0xd0548);
        iv = p.runtimeMount?recovered.clone():Arrays.copyOfRange(binary, 0xd0549, 0xd0559);
        cipher = ciphertext.clone(); original = crypt(ciphertext, Cipher.DECRYPT_MODE);
        require(sha(original).equals(p.plainSha), "原厂配置解码校验失败");
        require(Arrays.equals(crypt(original, Cipher.ENCRYPT_MODE), cipher), "原厂配置往返校验失败");
    }
    private byte[] crypt(byte[] input, int mode) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(input);
    }
    public byte[] original() { return original.clone(); }
    public byte[] make(String mode, int cap0, int cap6) throws Exception {
        return make(mode,cap0,cap6,25,originalCurve(false),originalCurve(true));
    }
    public byte[] make(String mode,int cap0,int cap6,int percent,int[] custom0,int[] custom6) throws Exception {
        return make(mode,cap0,0,cap6,percent,custom0,null,custom6);
    }
    public byte[] make(String mode,int cap0,int cap3,int cap6,int percent,int[] custom0,int[] custom3,int[] custom6) throws Exception {
        require(mode.equals("fullmax") || mode.equals("plus25") || mode.equals("boost") || mode.equals("custom"), "未知模式");
        require(percent>=0 && percent<=100,"增幅超出范围");
        require(contains(profile.table(0), cap0) && contains(profile.table(6), cap6) && (!profile.runtimeMount||contains(profile.table(3),cap3)), "频率不在设备频率表中");
        String source = new String(original, StandardCharsets.UTF_8), changed = source;
        changed = section(changed, "SS-CPU0", profile.table(0), cap0, mode,percent,custom0,profile.curve(0).length);
        if(profile.runtimeMount) changed=section(changed,"SS-CPU3",profile.table(3),cap3,mode,percent,custom3,profile.curve(3).length);
        changed = section(changed, "SS-CPU6", profile.table(6), cap6, mode,percent,custom6,profile.curve(6).length);
        String[] a = source.split("\n", -1), b = changed.split("\n", -1);
        require(a.length == b.length, "配置行数变化");
        int differences = 0;
        for (int i=0;i<a.length;i++) if (!a[i].equals(b[i])) {
            differences++; require(a[i].startsWith("target\t") && b[i].startsWith("target\t"), "非 CPU 目标被修改");
        }
        require(differences>=1 && differences<=profile.groups.length, "曲线与原厂相同，请调整频率或直接使用原厂策略");
        byte[] plain = changed.getBytes(StandardCharsets.UTF_8), encoded = crypt(plain, Cipher.ENCRYPT_MODE);
        require(Arrays.equals(crypt(encoded, Cipher.DECRYPT_MODE), plain), "新配置加密校验失败");
        return plain;
    }
    public byte[] encode(byte[] plain) throws Exception { return crypt(plain, Cipher.ENCRYPT_MODE); }
    private static String section(String text, String name, int[] table, int cap, String mode,int percent,int[] custom,int count) {
        Matcher m = Pattern.compile("(?ms)^\\[" + name + "\\]\\r?\\n.*?(?=^\\[|\\z)").matcher(text);
        require(m.find(), "缺少 CPU 曲线 " + name);
        String block = m.group();
        Matcher target = Pattern.compile("(?m)^target[\\t ]+([^\\r\\n]+)").matcher(block);
        require(target.find(), "缺少目标频率");
        String[] values = target.group(1).trim().split("\\s+");
        require(values.length == count, "曲线档位数不匹配");
        require(!mode.equals("custom") || (custom!=null && custom.length==values.length),"自定义曲线档位数不匹配");
        StringBuilder line = new StringBuilder("target");
        for (int i=0;i<values.length;i++) {
            int old = Integer.parseInt(values[i]); require(contains(table, old), "原始频率不在表中");
            int requested;
            if(mode.equals("custom")) { require(contains(table,custom[i]),"自定义频率不在设备频率表中"); requested=Math.min(cap,custom[i]); }
            else requested = mode.equals("fullmax") || i==0 ? cap : Math.min(cap, old*(100+percent)/100);
            int selected=table[0]; for (int f: table) if (f<=requested) selected=f;
            line.append('\t').append(selected);
        }
        String result = block.substring(0,target.start()) + line + block.substring(target.end());
        return text.substring(0,m.start()) + result + text.substring(m.end());
    }
    public static boolean contains(int[] table, int n) { for (int value: table) if (value==n) return true; return false; }
    public static String sha(byte[] input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
        StringBuilder out = new StringBuilder(); for (byte b: digest) out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
}
