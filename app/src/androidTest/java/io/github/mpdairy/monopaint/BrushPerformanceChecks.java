package io.github.mpdairy.monopaint;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/** Repeatable CPU replay and raster fingerprints; does not measure panel latency. */
final class BrushPerformanceChecks {
    static void run(StringBuilder report) throws Exception {
        for (int scenario=0;scenario<8;scenario++) {
            int size=scenario==7?256:scenario==0||scenario==5?64:128;
            ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.BRUSH)
                    .head(scenario==4?ToolSettings.Head.FILBERT:ToolSettings.Head.FLAT)
                    .size(size).tilt(true).headThickness(scenario==3?0:10)
                    .bristles(scenario==2?75:0);
            long[] times=new long[3];String fingerprint="";
            for(int run=-1;run<3;run++) {
                ToneDocument doc=new ToneDocument(768,512);
                long start=System.nanoTime();
                PressureStroke stroke=new PressureStroke(doc,settings,0,123);
                for(int i=0;i<120;i++)
                    stroke.sample(100+i*4,220+(float)Math.sin(i*.08)*80,
                            scenario>=5?.45f:.14f+(float)Math.sin(i*.11)*.06f,
                            (float)Math.sin(i*.07)*12,60);
                stroke.finish();
                long elapsed=System.nanoTime()-start;
                if(run>=0)times[run]=elapsed;
                byte[] hash=MessageDigest.getInstance("SHA-256").digest(doc.snapshot());
                StringBuilder hex=new StringBuilder();for(byte value:hash)hex.append(String.format(Locale.US,"%02x",value&255));
                if(run>=0&&!fingerprint.equals(hex.toString()))throw new AssertionError("Non-deterministic brush replay");
                fingerprint=hex.toString();
            }
            Arrays.sort(times);
            report.append(String.format(Locale.US,"case=%d head=%s size=%d thickness=%d texture=%d median_ms=%.2f per_sample_ms=%.3f sha256=%s%n",
                    scenario,settings.head,size,settings.headThickness,settings.bristles,
                    times[1]/1e6,times[1]/120e6,fingerprint));
        }
        toolReplay(report);
    }
    private static void toolReplay(StringBuilder report) {
        for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.BRUSH,ToolSettings.Tool.ERASER,ToolSettings.Tool.AIRBRUSH}) {
            long[] times=new long[3];
            for(int run=-1;run<3;run++) {
                ToneDocument doc=new ToneDocument(768,512);
                ToolSettings settings=ToolSettings.defaults(tool).size(128);
                if(tool==ToolSettings.Tool.ERASER) {
                    doc.begin();for(int y=0;y<doc.height;y++)for(int x=0;x<doc.width;x++)doc.paintTone(x,y,0);doc.finish();
                }
                long start=System.nanoTime();
                DrawingStroke stroke=tool==ToolSettings.Tool.AIRBRUSH?new AirbrushStroke(doc,settings,0)
                        :tool==ToolSettings.Tool.ERASER?new ToolStroke(doc,settings,255):new PressureStroke(doc,settings,0);
                for(int i=0;i<120;i++)stroke.sample(100+i*4,220+(float)Math.sin(i*.08)*80,.45f,0,60);
                stroke.finish();if(run>=0)times[run]=System.nanoTime()-start;
            }
            Arrays.sort(times);report.append(String.format(Locale.US,"tool=%s size=128 median_ms=%.2f per_sample_ms=%.3f%n",tool,times[1]/1e6,times[1]/120e6));
        }
    }
    private BrushPerformanceChecks() {}
}
