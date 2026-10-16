package dev.pulse.monitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Host JVM checks for the rate/time-window and shell-section parser. */
public final class MetricsMathTest {
    public static void main(String[] args) {
        long now=10_000_000_000L;
        near(FrameRateReader.recentRate(Collections.singletonList(now-2_000_000_000L),now),0,"stale frame is zero");
        near(FrameRateReader.recentRate(Arrays.asList(now-20_000_000L,now-20_000_000L),now),1,"timestamps deduplicate");
        check(Double.isNaN(FrameRateReader.recentRate(Collections.emptyList(),now)),"no samples unavailable");
        check(Double.isNaN(FrameRateReader.recentRate(Collections.singletonList(now+500_000_000L),now)),"future clock unavailable");
        List<Long> sixty=new ArrayList<>();
        for(int i=0;i<60;i++)sixty.add(now-5_000_000L-i*16_666_667L);
        near(FrameRateReader.recentRate(sixty,now),60,"60 Hz sample producer");
        List<Long> highRefresh=new ArrayList<>();
        for(int i=0;i<127;i++)highRefresh.add(now-5_000_000L-i*6_944_444L);
        near(FrameRateReader.recentRate(highRefresh,now),144,"full ring does not truncate 144 Hz");
        Map<String,String> fields=MetricsCollector.parseSections("@@stat\ncpu 1 2 3 4\ncpu0 1 2 3 4\n@@gpu_busy\n123 999\n@@empty\n@@freq_0\n960000\n");
        check(fields.get("stat").contains("cpu0"),"multiline retained");
        check(fields.get("empty").isEmpty(),"empty remains unavailable");
        check(fields.get("freq_0").equals("960000"),"last section retained");
        check(Double.isNaN(MetricsCollector.number("Permission denied")),"failed read never zero");
        check(MetricsCollector.number("0")==0,"real zero preserved");
        check(FrameRateReader.quote("a'b").equals("'a'\\''b'"),"layer name safely quoted");
        System.out.println("PASS: 12 telemetry math and parsing checks");
    }
    private static void near(double actual,double expected,String test) { check(Math.abs(actual-expected)<0.01,test+" got "+actual); }
    private static void check(boolean valid,String test) { if(!valid)throw new AssertionError(test); }
}
