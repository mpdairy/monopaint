package dev.tilesmile.supernote;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** App-local replay on an isolated temporary document; restores the user's document afterward. */
final class PaintChecks {
    static void run(Instrumentation test, StringBuilder report) throws Exception {
        rasterBaseline(); report.append("Logical brush matches 0.12 circle rasterization for all 16 shades.\n");
        rubbingChecks(report);
        stumpStrengthChecks(report);
        sizeRangeChecks(report);
        Intent intent = new Intent(test.getTargetContext(), PaintActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PaintActivity activity = (PaintActivity)test.startActivitySync(intent);
        PaintActivity current = activity;
        awaitReady(test, current);
        Object pad = field(current, "pad");
        ToneDocument original = (ToneDocument)field(pad, "document");
        DrawingBook originalBook=(DrawingBook)field(current,"book");
        boolean originalSide=((android.content.SharedPreferences)field(current,"preferences")).getBoolean("toolbox_right",false);
        int originalGray = (Integer)field(current, "gray"), originalMaximum = (Integer)field(current, "maximum");
        ToolLibrary originalLibrary = (ToolLibrary)field(current,"library");
        try {
            Object firstPad = pad;
            PaintActivity firstActivity = current;
            test.runOnMainSync(() -> {
                call(firstPad, "replace", new Class<?>[]{ToneDocument.class}, new ToneDocument(original.width, original.height));
                set(firstActivity, "gray", 128); set(firstActivity, "maximum", 64);
                set(firstActivity,"library",new ToolLibrary());
                call(firstActivity,"rebuildTools",new Class<?>[0]);
            });
            test.waitForIdleSync(); SystemClock.sleep(300);
            check(field(pad, "direct") != null, "Direct mode 7 display active");
            checkFirmwareArea(pad);
            report.append("Firmware default drawing area is replaced by an empty region.\n");
            ToneDocument doc = (ToneDocument)field(pad, "document");
            View view = (View)pad;
            long start = SystemClock.uptimeMillis();
            int drawCount = (Integer)field(pad, "drawCount");
            test.runOnMainSync(() -> {
                event(view, start, MotionEvent.ACTION_DOWN, 100, 150, .1f);
                event(view, start, MotionEvent.ACTION_MOVE, 260, 150, .45f);
                event(view, start, MotionEvent.ACTION_MOVE, 420, 150, .1f);
            });
            // Let the documented 8 ms coalescing interval elapse, then give it
            // another pen sample. A burst of replayed events can otherwise end
            // before its pending pixels are due for submission.
            SystemClock.sleep(12);
            final int[] driverPixel = new int[2];
            test.runOnMainSync(() -> {
                // Read the newly submitted patch immediately. The driver map
                // is shared with the compositor, not a retained screenshot.
                event(view,start,MotionEvent.ACTION_MOVE,428,150,.1f);
                try {
                    driverPixel[0] = ((DirectEink)field(firstPad,"direct")).readGray(428,150);
                    driverPixel[1] = (((Bitmap)field(firstPad,"display")).getPixel(428,150) >>> 16 & 255) / 16;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            check(doc.tone(260,150) == 128, "Logical gray while pen is down");
            int[] bounds = doc.dirty(); check(bounds == null, "Dirty tones rendered before pen-up");
            check(driverPixel[0] == driverPixel[1], "Driver receives derived dots: expected " + driverPixel[1] + ", actual " + driverPixel[0]);
            test.runOnMainSync(() -> event(view, start, MotionEvent.ACTION_UP, 428, 150, 0));
            test.waitForIdleSync(); SystemClock.sleep(100);
            check((Integer)field(pad, "drawCount") == drawCount, "No Android redraw during stroke or pen-up");
            check(doc.canUndo() && doc.tone(260,150) == 128, "Stroke committed");
            report.append("Gray paints live, driver pixels agree, and pen-up causes no Android redraw.\n");

            test.runOnMainSync(() -> {
                set(firstActivity, "gray", 0); set(firstActivity, "maximum", 64);
                event(view, start, MotionEvent.ACTION_DOWN, 260, 270, .45f);
                event(view, start, MotionEvent.ACTION_UP, 260, 270, 0);
                set(firstActivity, "gray", 255); set(firstActivity, "maximum", 16);
                event(view, start, MotionEvent.ACTION_DOWN, 260, 270, .45f);
                event(view, start, MotionEvent.ACTION_UP, 260, 270, 0);
            });
            check(doc.tone(260,270) == 255 && doc.tone(280,270) == 0, "White over logical black preserves surround");
            test.runOnMainSync(() -> findButton(firstActivity, "Undo").performClick());
            check(doc.tone(260,270) == 0, "Undo white stroke");
            test.runOnMainSync(() -> findButton(firstActivity, "Redo").performClick());
            check(doc.tone(260,270) == 255, "Redo white stroke");
            test.runOnMainSync(() -> { set(firstActivity, "gray", 128); set(firstActivity, "maximum", 128); });
            long largeStart = SystemClock.uptimeMillis();
            test.runOnMainSync(() -> {
                event(view, start, MotionEvent.ACTION_DOWN, 200, 450, .45f);
                for (int x = 208; x <= 1000; x += 8) event(view, start, MotionEvent.ACTION_MOVE, x, 450, .45f);
                event(view, start, MotionEvent.ACTION_UP, 1000, 450, 0);
            });
            long largeMillis = SystemClock.uptimeMillis() - largeStart;
            int diameter = 0;
            for (int y = 350; y < 550; y++) if (doc.tone(600,y) != 255) diameter++;
            check(diameter == 128, "Maximum brush diameter is 128 px");
            report.append("128 px brush stays capped; 101 controlled samples and commit took " + largeMillis + " ms (not panel latency).\n");
            checkTools(test,firstActivity,firstPad,doc,report);
            checkToolTaps(test,firstActivity,doc,report);
            checkLargeFill(test,firstActivity,firstPad,doc,report);
            checkSelectionFeedback(test,firstActivity,doc,report);
            byte[] expected = doc.snapshot();
            test.runOnMainSync(() -> {
                event(view, start, MotionEvent.ACTION_DOWN, 600, 500, 1, MotionEvent.TOOL_TYPE_FINGER);
                event(view, start, MotionEvent.ACTION_UP, 600, 500, 0, MotionEvent.TOOL_TYPE_FINGER);
            });
            check(Arrays.equals(expected, doc.snapshot()), "Palm/finger ignored");
            report.append("Black/white share the logical document; undo, redo and palm rejection pass.\n");
            checkPicker(test, current, report);
            DocumentStore store = (DocumentStore)field(current, "store");
            final Exception[] error = new Exception[1];
            store.recoverLater(new DocumentStore.Snapshot(doc), (value, failure) -> error[0] = failure);
            // Queue a read behind the coalescing writer; this is a durability barrier even if another save was pending.
            CountDownLatch loaded = new CountDownLatch(1);
            final ToneDocument[] restored = new ToneDocument[1];
            store.open("_recovery", (value, failure) -> { restored[0] = value; error[0] = failure; loaded.countDown(); });
            check(loaded.await(10, TimeUnit.SECONDS) && error[0] == null, "Recovery IO completed");
            check(Arrays.equals(expected, restored[0].snapshot()), "Atomic recovery contains logical tones");
            // Recreated stores must share the old Activity's writer queue.
            CountDownLatch nextRead = new CountDownLatch(1);
            store.recoverLater(new DocumentStore.Snapshot(doc), (value, failure) -> error[0] = failure);
            new DocumentStore(current.getFilesDir()).open("_recovery", (value, failure) -> {
                restored[0] = value; error[0] = failure; nextRead.countDown();
            });
            check(nextRead.await(10,TimeUnit.SECONDS) && error[0] == null
                    && Arrays.equals(expected,restored[0].snapshot()), "New store reads after previous store's save");
            namedSave(test,doc);
            report.append("Named local save/open and cross-Activity IO ordering pass.\n");
            byte[] expectedActive=checkPages(test,current,report);
            test.runOnMainSync(current::finish); test.waitForIdleSync();
            current = (PaintActivity)test.startActivitySync(intent); awaitReady(test, current);
            pad = field(current, "pad");
            check(Arrays.equals(expectedActive, ((ToneDocument)field(pad,"document")).snapshot()), "Activity restart recovers active page");
            DrawingBook recoveredBook=(DrawingBook)field(current,"book");
            check(recoveredBook.count()==2&&recoveredBook.index()==1,"Restart restores page count and position");
            int[] origin=new int[2];((View)pad).getLocationOnScreen(origin);
            check(origin[0]==0&&((android.content.SharedPreferences)field(current,"preferences")).getBoolean("toolbox_right",false),"Right-side toolbox survives restart");
            ToolLibrary restarted=(ToolLibrary)field(current,"library");
            check(restarted.presets().size()==1&&restarted.presets().get(0).name.equals("Renamed check preset")
                    &&restarted.presets().get(0).settings.soft&&restarted.presets().get(0).settings.maximum==9,"Edited custom settings survive Activity restart");
            report.append("Atomic recovery and Activity restart preserve exact logical tones.\n");
            presetDragChecks(test, current, report);
            customToolbarChecks(test, current, report);
        } finally {
            Object restorePad = field(current, "pad"); PaintActivity restoreActivity = current;
            test.runOnMainSync(() -> {
                call(restoreActivity, "replaceBook", new Class<?>[]{DrawingBook.class}, originalBook);
                try {((android.content.SharedPreferences)field(restoreActivity,"preferences")).edit().putBoolean("toolbox_right",originalSide).apply();}
                catch(Exception error){throw new IllegalStateException(error);}
                call(restoreActivity,"applyToolboxSide",new Class<?>[0]);
                set(restoreActivity, "gray", originalGray); set(restoreActivity, "maximum", originalMaximum);
                set(restoreActivity,"library",originalLibrary);
                call(restoreActivity,"rebuildTools",new Class<?>[0]);
                call(restoreActivity, "preferences", new Class<?>[0]);
                try { call(field(restoreActivity, "shadePreview"), "update", new Class<?>[0]); }
                catch (Exception error) { throw new IllegalStateException(error); }
                call(restoreActivity, "recovery", new Class<?>[0]);
            });
            CountDownLatch restored = new CountDownLatch(1);
            ((DocumentStore)field(current,"store")).open("_recovery", (d,e) -> restored.countDown());
            check(restored.await(10, TimeUnit.SECONDS), "Original document restoration completed");
        }
    }
    private static void customToolbarChecks(Instrumentation test, PaintActivity activity, StringBuilder report) throws Exception {
        ToolLibrary tools=new ToolLibrary();
        android.content.SharedPreferences prefs=(android.content.SharedPreferences)field(activity,"preferences");
        test.runOnMainSync(() -> {set(activity,"library",tools);call(activity,"rebuildTools",new Class<?>[0]);});
        for(ToolSettings.Tool tool:ToolSettings.Tool.values()) {
            test.runOnMainSync(() -> {
                tools.select(tool);set(activity,"maximum",tools.current().maximum);
                android.app.AlertDialog dialog=(android.app.AlertDialog)call(activity,"settings",new Class<?>[0]);
                try {
                    Button add=dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL);
                    check(add.getText().toString().equals("Add to Toolbar"),"Built-in settings offer Add to Toolbar");
                    add.performClick();
                    check(!dialog.isShowing()&&tools.presets().size()==1&&!tools.activeId().isEmpty(),"Adding saves immediately without a naming dialog");
                } finally {dialog.dismiss();}
                String id=tools.activeId();
                dialog=(android.app.AlertDialog)call(activity,"settings",new Class<?>[0]);
                try {
                    View root=dialog.getWindow().getDecorView();
                    check(findButton(root,"Manage custom preset")==null&&findButton(root,"Add to Toolbar")==null,"Custom settings have no management or add button");
                    String slider=tool==ToolSettings.Tool.FILL?"Tolerance":"Maximum diameter";
                    ((android.widget.SeekBar)findDescription(root,slider)).setProgress(41);
                    ((android.widget.SeekBar)findDescription(root,slider)).setProgress(53);
                    if(tool==ToolSettings.Tool.BRUSH) {
                        android.widget.SeekBar response=(android.widget.SeekBar)findDescription(root,"Pressure response");
                        check(response!=null && response.getProgress()==50,"Brush dialog exposes the original pressure response");
                        response.setProgress(85);
                        check(tools.current().pressureResponse==85,"Brush response slider updates settings");
                    }
                    if(tool==ToolSettings.Tool.PENCIL) ((android.widget.SeekBar)findDescription(root,"Hardness")).setProgress(81);
                    if(tool==ToolSettings.Tool.ERASER) ((android.widget.SeekBar)findDescription(root,"Softness")).setProgress(27);
                    if(tool==ToolSettings.Tool.SOFTEN) ((android.widget.SeekBar)findDescription(root,"Strength")).setProgress(62);
                    check(tools.activeId().equals(id)&&tools.presets().get(0).settings.equals(tools.current()),"Slider edits update the selected custom tool");
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                } finally {dialog.dismiss();}
                ToolSettings edited=tools.current();tools.select(tool);tools.recall(id);
                check(tools.current().equals(edited),"Edited custom tool recalls the new values");
                try {
                    ToolLibrary saved=ToolLibrary.decode(java.util.Base64.getDecoder().decode(prefs.getString("tools","")));
                    check(saved.activeId().equals(id)&&saved.presets().get(0).settings.equals(edited),"Dialog edits persist to preferences");
                } catch(Exception error) {throw new IllegalStateException(error);}
                dialog=(android.app.AlertDialog)call(activity,"settings",new Class<?>[0]);
                try {
                    Button delete=dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL);
                    check(delete.getText().length()==0&&delete.getCompoundDrawablesRelative()[0]!=null
                            &&"Delete custom tool".contentEquals(delete.getContentDescription()),"Delete is an accessible trash icon");
                    delete.performClick();
                    check(!dialog.isShowing()&&tools.presets().isEmpty()&&tools.activeId().isEmpty(),"Trash removes custom tool and closes settings");
                    try {
                        ToolLibrary saved=ToolLibrary.decode(java.util.Base64.getDecoder().decode(prefs.getString("tools","")));
                        check(saved.presets().isEmpty(),"Trash deletion persists to preferences");
                    } catch(Exception error) {throw new IllegalStateException(error);}
                } finally {dialog.dismiss();}
            });
            test.waitForIdleSync();
        }
        report.append("All five tool dialogs add without naming, save repeated slider edits, recall saved values, and delete with an accessible trash icon. No management button remains.\n");
    }
    private static View findDescription(View view,String description) {
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) return view;
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=findDescription(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;
        }
        return null;
    }
    private static void presetDragChecks(Instrumentation test, PaintActivity activity, StringBuilder report) throws Exception {
        ToolLibrary tools = new ToolLibrary();
        ToolLibrary.Preset a = tools.add("Drag A"), b = tools.add("Drag B"), c = tools.add("Drag C");
        tools.recall(b.id);
        ToneDocument document = (ToneDocument)field(field(activity,"pad"),"document");
        byte[] original = document.snapshot();
        android.widget.ScrollView scroll = (android.widget.ScrollView)field(activity,"toolScroll");
        android.content.SharedPreferences prefs = (android.content.SharedPreferences)field(activity,"preferences");
        test.runOnMainSync(() -> {set(activity,"library",tools);call(activity,"rebuildTools",new Class<?>[0]);scroll.scrollTo(0,0);});
        test.waitForIdleSync();
        for (boolean right : new boolean[]{false,true}) {
            test.runOnMainSync(() -> {prefs.edit().putBoolean("toolbox_right",right).apply();call(activity,"applyToolboxSide",new Class<?>[0]);});
            test.waitForIdleSync();
            checkHeader(activity);
            Button first = findButton(activity,"Drag A"), last = findButton(activity,"Drag C");
            int[] start = new int[2], end = new int[2]; first.getLocationOnScreen(start);last.getLocationOnScreen(end);
            drag(test, start[0]+first.getWidth()/2f, start[1]+first.getHeight()/2f,
                    end[0]+last.getWidth()/2f, end[1]+last.getHeight()-2);
            check(tools.presets().get(2).id.equals(a.id), "Native drag moves preset to end on either toolbox side");
            first = findButton(activity,"Drag A");last = findButton(activity,"Drag B");
            first.getLocationOnScreen(start);last.getLocationOnScreen(end);
            drag(test, start[0]+first.getWidth()/2f, start[1]+first.getHeight()/2f,
                    end[0]+last.getWidth()/2f, end[1]+2);
            check(tools.presets().get(0).id.equals(a.id) && tools.presets().get(1).id.equals(b.id), "Native drag inserts before first preset");
            first = findButton(activity,"Drag A");first.getLocationOnScreen(start);
            View canvas = (View)field(activity,"pad");canvas.getLocationOnScreen(end);
            drag(test, start[0]+first.getWidth()/2f, start[1]+first.getHeight()/2f,
                    end[0]+canvas.getWidth()/2f, end[1]+canvas.getHeight()/2f);
            check(tools.presets().get(0).id.equals(a.id) && first.getAlpha()==1, "Drop outside rail cancels and restores source appearance");
        }
        ToolLibrary saved = ToolLibrary.decode(java.util.Base64.getDecoder().decode(prefs.getString("tools","")));
        check(saved.presets().get(0).id.equals(a.id) && saved.presets().get(2).id.equals(c.id)
                && saved.activeId().equals(b.id), "Native drops persist order without changing selected preset");
        test.runOnMainSync(() -> {
            for (int i=0;i<25;i++) tools.add("Drag scroll " + i);
            tools.recall(b.id);call(activity,"rebuildTools",new Class<?>[0]);scroll.scrollTo(0,0);
        });
        test.waitForIdleSync();
        Button first = findButton(activity,"Drag A");int[] start = new int[2], edge = new int[2];
        first.getLocationOnScreen(start);scroll.getLocationOnScreen(edge);
        drag(test,start[0]+first.getWidth()/2f,start[1]+first.getHeight()/2f,
                edge[0]+scroll.getWidth()/2f,edge[1]+scroll.getHeight()-10,800);
        check(scroll.getScrollY()>0 && tools.presets().indexOf(a)>2, "Holding dragged preset at bottom scrolls and drops into offscreen list");
        check(Arrays.equals(original,document.snapshot()), "Dragging presets never marks the canvas");
        report.append("Native long-press drag inserts presets upward/downward on both sides, scrolls at the edge, cancels outside the rail, saves order and preserves drawing/selection.\n");
    }
    private static void drag(Instrumentation test, float x, float y, float endX, float endY) {
        drag(test,x,y,endX,endY,0);
    }
    private static void drag(Instrumentation test, float x, float y, float endX, float endY, int holdMillis) {
        long down = SystemClock.uptimeMillis();
        pointer(test, down, MotionEvent.ACTION_DOWN, x, y);
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+200);
        for (int step=1;step<=12;step++) {
            pointer(test, down, MotionEvent.ACTION_MOVE, x+(endX-x)*step/12, y+(endY-y)*step/12);
            SystemClock.sleep(35);
        }
        SystemClock.sleep(holdMillis);
        pointer(test, down, MotionEvent.ACTION_UP, endX, endY);
        test.waitForIdleSync(); SystemClock.sleep(150);
    }
    private static void pointer(Instrumentation test,long down,int action,float x,float y) {
        MotionEvent event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        // System drag dispatch owns the gesture after long-press, so use the UI automation input path.
        check(test.getUiAutomation().injectInputEvent(event,true), "Drag pointer injected");event.recycle();
    }
    private static void checkHeader(PaintActivity activity) throws Exception {
        View picker=(View)field(activity,"shadePicker"), palette=(View)field(activity,"palette");
        int[] shade=new int[2], header=new int[2], action=new int[2], page=new int[2];
        picker.getLocationOnScreen(shade);palette.getLocationOnScreen(header);
        check(Math.abs(shade[0]+picker.getWidth()/2f-header[0]-palette.getWidth()/2f)<=1,
                "Grayscale strip stays centered on screen");
        for(String name:new String[]{"Undo","Redo","Clear canvas"}) {
            Button button=findButton(activity,name);button.getLocationOnScreen(action);
            check(action[0]+button.getWidth()<=shade[0], "Page action stays left of color selector");
        }
        ((View)field(activity,"previousPage")).getLocationOnScreen(page);
        check(page[0]>=shade[0]+picker.getWidth(), "Page selector stays right of color selector");
    }
    private static byte[] checkPages(Instrumentation test,PaintActivity activity,StringBuilder report) throws Exception {
        DrawingBook book=(DrawingBook)field(activity,"book");byte[] first=book.current().snapshot();
        View add=(View)field(activity,"addPage"),previous=(View)field(activity,"previousPage"),next=(View)field(activity,"nextPage");
        test.runOnMainSync(add::performClick);test.waitForIdleSync();
        check(book.count()==2&&book.index()==1,"Add page appends and selects a new page");
        for(byte tone:book.current().snapshot())check((tone&255)==255,"New page starts white");
        Object pad=field(activity,"pad");View canvas=(View)pad;long start=SystemClock.uptimeMillis();
        ToolLibrary tools=(ToolLibrary)field(activity,"library");
        test.runOnMainSync(() -> {
            tools.select(ToolSettings.Tool.BRUSH);set(activity,"maximum",32);set(activity,"gray",0);
            event(canvas,start,MotionEvent.ACTION_DOWN,200,200,.45f);event(canvas,start,MotionEvent.ACTION_UP,200,200,0);
        });
        byte[] second=book.current().snapshot();check(book.current().tone(200,200)==0,"New page accepts drawing events");
        test.runOnMainSync(previous::performClick);test.waitForIdleSync();
        check(Arrays.equals(first,book.current().snapshot()),"Previous page retains all marks");
        test.runOnMainSync(next::performClick);test.waitForIdleSync();
        check(Arrays.equals(second,book.current().snapshot())&&book.current().canUndo(),"Next page retains marks and recent undo");
        android.content.SharedPreferences preferences=(android.content.SharedPreferences)field(activity,"preferences");
        for(boolean right:new boolean[]{false,true}) {
            test.runOnMainSync(() -> {preferences.edit().putBoolean("toolbox_right",right).apply();call(activity,"applyToolboxSide",new Class<?>[0]);});
            test.waitForIdleSync();SystemClock.sleep(100);
            int[] origin=new int[2];canvas.getLocationOnScreen(origin);
            check((origin[0]==0)==right&&field(pad,"direct")!=null,"Toolbox moves sides and reconnects direct display");
            check(Arrays.equals(second,book.current().snapshot()),"Moving controls leaves page pixels untouched");
        }
        DocumentStore store=(DocumentStore)field(activity,"store");CountDownLatch saved=new CountDownLatch(1);Exception[] error={null};
        String name="_page_checks_"+SystemClock.uptimeMillis();
        store.save(name,new DocumentStore.Snapshot(book),(unused,failure)->{error[0]=failure;saved.countDown();});
        check(saved.await(10,TimeUnit.SECONDS)&&error[0]==null,"Whole-book save completes");
        CountDownLatch opened=new CountDownLatch(1);DrawingBook[] loaded={null};
        store.openBook(name,(value,failure)->{loaded[0]=value;error[0]=failure;opened.countDown();});
        check(opened.await(10,TimeUnit.SECONDS)&&error[0]==null&&loaded[0].count()==2&&loaded[0].index()==1,"Named save/open preserves pages and position");
        check(Arrays.equals(second,loaded[0].current().snapshot()),"Saved active page is exact");loaded[0].select(0);check(Arrays.equals(first,loaded[0].current().snapshot()),"Saved earlier page is exact");
        new java.io.File(activity.getFilesDir(),"drawings/"+name+".tsm").delete();
        CountDownLatch barrier=new CountDownLatch(1);store.openBook("_recovery",(value,failure)->{error[0]=failure;barrier.countDown();});
        check(barrier.await(10,TimeUnit.SECONDS)&&error[0]==null,"Whole-book recovery finishes");
        report.append("Blank-page creation, navigation, recent-page undo, whole-book save/open and both toolbox sides pass.\n");
        return second;
    }
    private static void checkSelectionFeedback(Instrumentation test, PaintActivity activity, ToneDocument doc, StringBuilder report) throws Exception {
        SelectionFeedback feedback=(SelectionFeedback)field(activity,"selectionFeedback");
        ToolLibrary savedLibrary=(ToolLibrary)field(activity,"library");int savedMaximum=(Integer)field(activity,"maximum");
        boolean original=feedback.enabled;byte[] tones=doc.snapshot();boolean undo=doc.canUndo(),redo=doc.canRedo();
        Button pencil=findButton(activity,"Pencil");
        Bitmap[] baseline=new Bitmap[1],restored=new Bitmap[1];
        test.runOnMainSync(() -> {
            feedback.enabled=true;
            call(activity,"markActive",new Class<?>[]{Button.class,boolean.class},pencil,false);
            findButton(activity,"Brush").performClick();
        });
        test.waitForIdleSync();
        test.runOnMainSync(() -> baseline[0]=controlBitmap(pencil));
        int requests=feedback.submitted;
        try {
            checkFeedbackRepaint(test,activity,feedback,report);
            for(int i=0;i<4;i++) {
                final int shade=i%2==0?255:0;
                test.runOnMainSync(() -> {
                    findButton(activity,"Pencil").performClick();
                    call(activity,"selectShade",new Class<?>[]{int.class},shade);
                    findButton(activity,"Brush").performClick();
                });
                test.waitForIdleSync();SystemClock.sleep(20);
            }
            test.runOnMainSync(() -> {
                pencil.performClick();
                Bitmap selected=controlBitmap(pencil);
                android.graphics.Rect marker=(android.graphics.Rect)call(pencil,"markerArea",new Class<?>[0]);
                android.graphics.Rect changed=SelectionFeedback.difference(baseline[0],selected);
                check(!changed.isEmpty() && marker.contains(changed),"Instant tool selection changes only the corner dot, leaving border and icon intact");
                check(pencil.isSelected(),"Instant dot retains accessible selected state");
                selected.recycle();findButton(activity,"Brush").performClick();
            });
            check(feedback.submitted>requests,"Control-only direct requests accepted");
            android.graphics.Rect region=feedback.lastScreenRegion;
            View canvas=(View)field(activity,"pad");int[] canvasOrigin=new int[2];canvas.getLocationOnScreen(canvasOrigin);
            check(region.right<=canvasOrigin[0]||region.left>=canvasOrigin[0]+canvas.getWidth()||region.bottom<=canvasOrigin[1],"Selection request excludes document canvas");
            test.runOnMainSync(() -> restored[0]=controlBitmap(pencil));
            check(baseline[0].sameAs(restored[0]),"Changing tools restores exact inactive control pixels after redraw");
            check(!(Boolean)field(pencil,"marked")&&(Boolean)field(findButton(activity,"Brush"),"marked"),"Only latest tool marker is retained");
            ToolLibrary longList=ToolLibrary.decode(savedLibrary.encode());
            for(int i=0;i<30;i++)longList.add("Scroll check " + i);
            longList.select(ToolSettings.Tool.BRUSH);
            test.runOnMainSync(() -> {set(activity,"library",longList);call(activity,"rebuildTools",new Class<?>[0]);});
            test.waitForIdleSync();
            View rail=(View)field(activity,"toolRail");
            test.runOnMainSync(() -> {
                View divider=((ViewGroup)rail).getChildAt(ToolSettings.Tool.values().length);
                Bitmap separator=controlBitmap(divider);
                int runs=0;boolean inDash=false,hasGap=false;
                for(int x=0;x<separator.getWidth();x++) {
                    int pixel=separator.getPixel(x,separator.getHeight()/2);
                    boolean black=Color.alpha(pixel)>0 && Color.red(pixel)==0;
                    if(black&&!inDash)runs++;
                    if(!black)hasGap=true;
                    inDash=black;
                }
                check(runs>=4 && hasGap,"Divider between built-in and custom tools renders visible separate black dashes");
                separator.recycle();
            });
            android.widget.ScrollView scroll=(android.widget.ScrollView)rail.getParent();
            test.runOnMainSync(() -> scroll.scrollTo(0,rail.getHeight()));test.waitForIdleSync();
            check(scroll.getScrollY()>findButton(activity,"Pencil").getBottom(),"Long preset list scrolls controls offscreen");
            int before=feedback.submitted;
            test.runOnMainSync(() -> findButton(activity,"Pencil").performClick());
            check(feedback.submitted==before,"Offscreen controls never issue direct writes");
            test.runOnMainSync(() -> scroll.scrollTo(0,0));
            android.app.AlertDialog[] dialog=new android.app.AlertDialog[1];
            test.runOnMainSync(() -> {dialog[0]=new android.app.AlertDialog.Builder(activity).setMessage("Selection feedback check").create();dialog[0].show();});
            test.waitForIdleSync();SystemClock.sleep(100);
            before=feedback.submitted;
            try {
                check(!rail.hasWindowFocus(),"Modal owns focus");
                test.runOnMainSync(() -> call(activity,"selectShade",new Class<?>[]{int.class},128));
                check(feedback.submitted==before,"Covered controls never issue direct writes");
            } finally {test.runOnMainSync(() -> dialog[0].dismiss());}
            test.waitForIdleSync();
            check(Arrays.equals(tones,doc.snapshot())&&undo==doc.canUndo()&&redo==doc.canRedo(),"Selection overlays do not enter document or history");
            report.append("Optional selection feedback changes only the tool dot (fixed border) and submits bounded control patches; rapid changes restore backgrounds, scrolling and modal focus suppress stale writes, and document/history remain untouched.\n");
        } finally {
            test.runOnMainSync(() -> {
                feedback.enabled=original;set(activity,"library",savedLibrary);set(activity,"maximum",savedMaximum);
                call(activity,"rebuildTools",new Class<?>[0]);call(activity,"preferences",new Class<?>[0]);
            });
            baseline[0].recycle();if(restored[0]!=null)restored[0].recycle();
        }
    }
    private static void checkFeedbackRepaint(Instrumentation test, PaintActivity activity, SelectionFeedback feedback, StringBuilder report) throws Exception {
        View root=activity.getWindow().getDecorView();
        Button pencil=findButton(activity,"Pencil"),brush=findButton(activity,"Brush");
        View picker=(View)field(activity,"shadePicker"),preview=(View)field(activity,"shadePreview");
        java.util.concurrent.atomic.AtomicInteger frames=new java.util.concurrent.atomic.AtomicInteger();
        android.view.ViewTreeObserver.OnDrawListener listener=() -> frames.incrementAndGet();
        test.waitForIdleSync();SystemClock.sleep(600);
        test.runOnMainSync(() -> root.getViewTreeObserver().addOnDrawListener(listener));
        try {
            int before=feedback.submitted;
            test.runOnMainSync(pencil::performClick);
            test.waitForIdleSync();SystemClock.sleep(800);
            check(feedback.submitted>before,"Tool selection reached direct display");
            check(frames.get()==0,"Direct tool selection must not queue a later Android frame: "+frames.get());
            before=feedback.submitted;
            test.runOnMainSync(() -> {
                long now=SystemClock.uptimeMillis();
                event(brush,now,MotionEvent.ACTION_DOWN,brush.getWidth()/2f,brush.getHeight()/2f,.2f);
                event(brush,now,MotionEvent.ACTION_UP,brush.getWidth()/2f,brush.getHeight()/2f,0);
            });
            test.waitForIdleSync();SystemClock.sleep(800);
            check(brush.isSelected() && feedback.submitted>before,"Stylus tap selects the tool directly");
            check(frames.get()==0,"Pressed/released tool must not animate a later repaint: "+frames.get());
            before=feedback.submitted;
            int oldGray=(Integer)field(activity,"gray");float x=oldGray==0?picker.getWidth()-2:2;
            test.runOnMainSync(() -> {
                long now=SystemClock.uptimeMillis();
                event(picker,now,MotionEvent.ACTION_DOWN,x,picker.getHeight()/2f,.2f);
                event(picker,now,MotionEvent.ACTION_UP,x,picker.getHeight()/2f,0);
            });
            test.waitForIdleSync();SystemClock.sleep(800);
            check(feedback.submitted>=before+2,"Shade marker and preview both reach direct display");
            check(frames.get()==0,"Direct shade selection and preview must not queue a later Android frame: "+frames.get());
            // An unrelated redraw must refresh the retained Android commands too.
            test.runOnMainSync(root::invalidate);test.waitForIdleSync();SystemClock.sleep(300);
            Bitmap screen=test.getUiAutomation().takeScreenshot();
            check(screen!=null,"Capture compositor after unrelated redraw");
            try {
                int[] location=new int[2];preview.getLocationOnScreen(location);
                int expected=(Integer)field(activity,"gray");
                check(Color.red(screen.getPixel(location[0]+preview.getWidth()/2,location[1]+preview.getHeight()/2))==expected,"Next normal frame retains the latest shade preview");
                brush.getLocationOnScreen(location);
                int inset=Math.round(12*activity.getResources().getDisplayMetrics().density);
                check(Color.red(screen.getPixel(location[0]+brush.getWidth()-inset,location[1]+inset))==0,"Next normal frame retains the latest tool dot");
            } finally {screen.recycle();}
            report.append("Direct tool clicks, stylus press/release and shade taps produce no Android frame for 800 ms; next unrelated redraw retains current marker and shade preview.\n");
        } finally {
            test.runOnMainSync(() -> root.getViewTreeObserver().removeOnDrawListener(listener));
        }
    }
    private static Bitmap controlBitmap(View view) {
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));return bitmap;
    }
    private static void checkToolTaps(Instrumentation test,PaintActivity activity,ToneDocument doc,StringBuilder report) throws Exception {
        ToolLibrary library=(ToolLibrary)field(activity,"library");byte[] before=doc.snapshot();
        test.runOnMainSync(() -> call(activity,"rebuildTools",new Class<?>[0]));
        for(String name:new String[]{"Pencil","Renamed check preset"}) {
            test.runOnMainSync(() -> findButton(activity,name).performClick());test.waitForIdleSync();SystemClock.sleep(80);
            check(activity.hasWindowFocus()&&findButton(activity,name).isSelected(),"First tap selects "+name+" without a dialog");
            ToolSettings selected=library.current();
            test.runOnMainSync(() -> findButton(activity,name).performClick());test.waitForIdleSync();SystemClock.sleep(80);
            check(!activity.hasWindowFocus()&&selected.equals(library.current()),"Second tap opens settings without resetting "+name);
            test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);test.waitForIdleSync();SystemClock.sleep(80);
        }
        check(Arrays.equals(before,doc.snapshot()),"Selecting and opening settings never paints");
        test.runOnMainSync(() -> {check(doc.clear(),"Temporary canvas clears");check(doc.undo(),"Clear undo available");});
        check(Arrays.equals(before,doc.snapshot()),"Clear restores all disconnected marks with one undo");
        report.append("First tap recalls a tool/preset; second tap opens settings without resetting it. Clear is one undoable action.\n");
    }
    private static void checkLargeFill(Instrumentation test,PaintActivity activity,Object pad,ToneDocument restore,StringBuilder report) throws Exception {
        ToneDocument blank=new ToneDocument(restore.width,restore.height);View view=(View)pad;
        long start=SystemClock.uptimeMillis();
        CountDownLatch responsive=new CountDownLatch(1);
        test.runOnMainSync(() -> {
            call(pad,"replace",new Class<?>[]{ToneDocument.class},blank);
            if(!findButton(activity,"Fill").isSelected())findButton(activity,"Fill").performClick();set(activity,"gray",128);
            event(view,start,MotionEvent.ACTION_DOWN,10,10,.45f);event(view,start,MotionEvent.ACTION_UP,10,10,0);
            view.post(responsive::countDown);
        });
        check(responsive.await(1,TimeUnit.SECONDS),"Main thread yields while filling large canvas");
        long end=SystemClock.uptimeMillis()+15000;
        while(field(pad,"fill")!=null&&SystemClock.uptimeMillis()<end)SystemClock.sleep(25);
        check(field(pad,"fill")==null&&blank.tone(blank.width-1,blank.height-1)==128,"Full canvas fill completes");
        long elapsed=SystemClock.uptimeMillis()-start;
        test.runOnMainSync(() -> findButton(activity,"Undo").performClick());
        check(!blank.canUndo()&&blank.tone(0,0)==255&&blank.tone(blank.width-1,blank.height-1)==255,"Large fill is one undo");
        test.runOnMainSync(() -> {event(view,start,MotionEvent.ACTION_DOWN,10,10,.45f);event(view,start,MotionEvent.ACTION_UP,10,10,0);});
        SystemClock.sleep(25);
        test.runOnMainSync(() -> call(pad,"finishStroke",new Class<?>[0]));
        for(byte tone:blank.snapshot())check((tone&255)==255,"Interrupted fill restores all original pixels");
        test.runOnMainSync(() -> call(pad,"replace",new Class<?>[]{ToneDocument.class},restore));
        report.append("Full-canvas fill completed in "+elapsed+" ms with a responsive UI; interrupted fill rolls back.\n");
    }
    private static void checkTools(Instrumentation test, PaintActivity activity, Object pad, ToneDocument doc, StringBuilder report) throws Exception {
        View view=(View)pad; long start=SystemClock.uptimeMillis();
        ToolLibrary library=(ToolLibrary)field(activity,"library");
        test.runOnMainSync(() -> {
            library.edit(ToolSettings.defaults(ToolSettings.Tool.BRUSH).size(128)); set(activity,"maximum",128);set(activity,"gray",0);
            event(view,start,MotionEvent.ACTION_DOWN,600,800,.45f);event(view,start,MotionEvent.ACTION_UP,600,800,0);
            findButton(activity,"Eraser").performClick();
            library.edit(library.current().size(32));set(activity,"maximum",32);
            event(view,start,MotionEvent.ACTION_DOWN,600,800,.45f);event(view,start,MotionEvent.ACTION_UP,600,800,0);
        });
        check(doc.tone(600,800)>0&&doc.tone(600,800)<255&&doc.tone(640,800)==0,"Eraser contact lifts only part of the mark within its footprint");
        test.runOnMainSync(() -> findButton(activity,"Undo").performClick());
        check(doc.tone(600,800)==0,"Eraser is one undo action");
        test.runOnMainSync(() -> {
            library.edit(library.current().softness(0));
            event(view,start,MotionEvent.ACTION_DOWN,600,800,.45f);
            event(view,start,MotionEvent.ACTION_MOVE,620,800,.45f);
            event(view,start,MotionEvent.ACTION_UP,620,800,0);
        });
        check(doc.tone(600,800)==255&&doc.tone(620,800)==255&&doc.tone(640,800)==0,"Zero-softness eraser is opaque and ignores the selected black shade");
        for(int y=770;y<830;y++)for(int x=570;x<645;x++)check(doc.tone(x,y)==0||doc.tone(x,y)==255,"Hard eraser leaves no gray fringe");
        test.runOnMainSync(() -> findButton(activity,"Undo").performClick());
        check(doc.tone(600,800)==0&&doc.tone(620,800)==0,"Hard eraser undoes the entire stroke once");
        test.runOnMainSync(() -> {
            library.edit(library.current().options(3,true,false));
            event(view,start,MotionEvent.ACTION_DOWN,600,800,.45f);event(view,start,MotionEvent.ACTION_UP,600,800,0);
        });
        check(doc.tone(600,800)>0&&doc.tone(600,800)<255&&doc.tone(640,800)==0,"Soft eraser lightens without disturbing outside pixels");
        test.runOnMainSync(() -> {
            doc.begin();for(int y=940;y<1060;y++)for(int x=940;x<1060;x++)doc.setTone(x,y,x<1000?0:255);doc.finish();
            call(pad,"renderDirty",new Class<?>[0]);call(pad,"present",new Class<?>[0]);
            findButton(activity,"Soften").performClick();set(activity,"maximum",32);
            event(view,start,MotionEvent.ACTION_DOWN,1000,1000,.45f);event(view,start,MotionEvent.ACTION_UP,1000,1000,0);
        });
        check(doc.tone(999,1000)>0&&doc.tone(1000,1000)<255,"Soften blends existing boundary tones");
        test.runOnMainSync(() -> {
            findButton(activity,"Pencil").performClick();set(activity,"maximum",64);set(activity,"gray",0);
            tiltEvent(view,start,MotionEvent.ACTION_DOWN,400,1100,.45f,0,0);tiltEvent(view,start,MotionEvent.ACTION_UP,400,1100,0,0,0);
            tiltEvent(view,start,MotionEvent.ACTION_DOWN,500,1100,.45f,65,0);tiltEvent(view,start,MotionEvent.ACTION_UP,500,1100,0,65,0);
            tiltEvent(view,start,MotionEvent.ACTION_DOWN,600,1100,.45f,0,65);tiltEvent(view,start,MotionEvent.ACTION_UP,600,1100,0,0,65);
        });
        int[] upright=bounds(doc,400,1100,40),horizontal=bounds(doc,500,1100,40),vertical=bounds(doc,600,1100,40);
        check(horizontal[0]>upright[0]*3&&horizontal[0]>horizontal[1]*2,"Pencil tilt broadens directional contact");
        check(vertical[1]>vertical[0]*2&&horizontal[0]<=64&&vertical[1]<=64,"Tilt rotates pencil within selected cap");
        int colorBefore=(Integer)field(activity,"gray");
        final String[] presetId=new String[1];
        test.runOnMainSync(() -> {
            library.edit(ToolSettings.defaults(ToolSettings.Tool.ERASER).size(23).options(3,true,false));
            presetId[0]=library.add("Device check preset").id;
            library.select(ToolSettings.Tool.BRUSH);call(activity,"rebuildTools",new Class<?>[0]);
            findButton(activity,"Device check preset").performClick();
        });
        check(library.current().tool==ToolSettings.Tool.ERASER&&library.current().soft&&library.current().maximum==23,"Custom button recalls all tool settings");
        check((Integer)field(activity,"gray")==colorBefore,"Preset recall keeps global shade");
        test.runOnMainSync(() -> {
            library.edit(library.current().size(9));
            check(library.presets().get(0).settings.maximum==9&&library.activeId().equals(presetId[0]),"Custom edits save and keep selection");
            library.rename(presetId[0],"Renamed check preset");call(activity,"preferences",new Class<?>[0]);
            doc.begin();for(int y=1200;y<=1300;y++)for(int x=800;x<=900;x++)doc.setTone(x,y,x==800||x==900||y==1200||y==1300?0:182);doc.finish();
            call(pad,"renderDirty",new Class<?>[0]);call(pad,"present",new Class<?>[0]);
            findButton(activity,"Fill").performClick();set(activity,"gray",249);
            event(view,start,MotionEvent.ACTION_DOWN,850,1250,.45f);event(view,start,MotionEvent.ACTION_UP,850,1250,0);
        });
        long end=SystemClock.uptimeMillis()+15000;
        while(field(pad,"fill")!=null&&SystemClock.uptimeMillis()<end)SystemClock.sleep(25);
        check(field(pad,"fill")==null&&doc.tone(850,1250)==249&&doc.tone(800,1250)==0&&doc.tone(799,1250)==255,"Fill uses exact logical region boundaries");
        test.runOnMainSync(() -> findButton(activity,"Undo").performClick());check(doc.tone(850,1250)==182,"Fill is one undo action");
        test.runOnMainSync(() -> findButton(activity,"Redo").performClick());check(doc.tone(850,1250)==249,"Fill redo restores target");
        test.runOnMainSync(() -> {
            doc.begin();for(int y=1201;y<1300;y++)for(int x=801;x<900;x++)doc.setTone(x,y,x<830?192:x>870?205:182);doc.finish();
            call(pad,"renderDirty",new Class<?>[0]);call(pad,"present",new Class<?>[0]);
            library.edit(library.current().tolerance(8));set(activity,"gray",182);
            event(view,start,MotionEvent.ACTION_DOWN,850,1250,.45f);event(view,start,MotionEvent.ACTION_UP,850,1250,0);
        });
        end=SystemClock.uptimeMillis()+15000;
        while(field(pad,"fill")!=null&&SystemClock.uptimeMillis()<end)SystemClock.sleep(25);
        check(field(pad,"fill")==null&&doc.tone(810,1250)==182&&doc.tone(880,1250)==205&&doc.tone(800,1250)==0,"Live fill honors tolerance from a same-color seed and preserves boundaries");
        test.runOnMainSync(() -> {findButton(activity,"Undo").performClick();library.edit(library.current().tolerance(0));});
        check(doc.tone(810,1250)==192&&doc.tone(850,1250)==182,"Tolerant fill undoes once");
        report.append("Live fill tolerance includes nearby shades, stops at the seed range and undoes once.\n");
        report.append("Opaque crisp zero-softness eraser, gradual soft eraser, stronger soften, directional tilt pencil, exact fill, and one-tap color-independent presets pass.\n");
    }
    private static void rubbingChecks(StringBuilder report) {
        for(ToolSettings.Tool tool:new ToolSettings.Tool[]{ToolSettings.Tool.ERASER,ToolSettings.Tool.SOFTEN}) {
            byte[] initial=new byte[256*128];
            for(int y=0;y<128;y++)for(int x=0;x<256;x++)initial[y*256+x]=(byte)(tool==ToolSettings.Tool.SOFTEN&&x>=128?255:0);
            ToneDocument sparse=new ToneDocument(256,128,initial),dense=new ToneDocument(256,128,initial);
            ToolSettings settings=ToolSettings.defaults(tool).size(100);
            ToolStroke a=new ToolStroke(sparse,settings,0),b=new ToolStroke(dense,settings,0);
            a.sample(28,64,.45f,0,0);b.sample(28,64,.45f,0,0);
            byte[] contact=dense.snapshot();
            for(int i=0;i<100;i++)b.sample(28,64,.45f,0,0);
            check(Arrays.equals(contact,dense.snapshot()),tool+" stationary samples do not accumulate");
            for(int x=38;x<=228;x+=10)a.sample(x,64,.45f,0,0);
            for(int x=29;x<=228;x++)b.sample(x,64,.45f,0,0);
            check(Arrays.equals(sparse.snapshot(),dense.snapshot()),tool+" rubbing is independent of event density");
            int before=dense.tone(127,64);
            long edgeBefore=edgeEnergy(dense);
            b.sample(28,64,.45f,0,0);b.sample(228,64,.45f,0,0);
            check(tool==ToolSettings.Tool.ERASER?dense.tone(127,64)>before:edgeEnergy(dense)<edgeBefore,
                    tool+" repeated rubbing lightens erasure or reduces edge contrast");
            a.finish();b.finish();dense.undo();check(Arrays.equals(initial,dense.snapshot()),tool+" entire rub undoes as one gesture");
        }
        ToneDocument doc=new ToneDocument(1200,256);doc.begin();for(int y=0;y<128;y++)for(int x=0;x<1200;x++)doc.setTone(x,y,0);doc.finish();
        long start=SystemClock.uptimeMillis();
        ToolStroke stroke=new ToolStroke(doc,ToolSettings.defaults(ToolSettings.Tool.SOFTEN).size(128),0);
        for(int x=200;x<=1000;x+=8)stroke.sample(x,128,.45f,0,0);stroke.finish();
        report.append("Rubbing tools ignore stationary samples, agree across input densities, build with repeated passes and undo once. 128 px soften model replay: "+(SystemClock.uptimeMillis()-start)+" ms.\n");
    }
    private static void stumpStrengthChecks(StringBuilder report) {
        byte[] original=new byte[256*128];for(int y=0;y<128;y++)for(int x=128;x<256;x++)original[y*256+x]=(byte)255;
        long previous=-1;
        for(int strength:new int[]{0,35,100}) {
            ToneDocument doc=new ToneDocument(256,128,original);
            ToolStroke stroke=new ToolStroke(doc,ToolSettings.defaults(ToolSettings.Tool.SOFTEN).size(64).strength(strength),0);
            for(int x=80;x<=170;x+=2)stroke.sample(x,64,.45f,0,0);
            boolean changed=stroke.finish();long difference=0;byte[] actual=doc.snapshot();
            for(int i=0;i<actual.length;i++)difference+=Math.abs((actual[i]&255)-(original[i]&255));
            check(difference>previous,"Live stump stroke respects strength "+strength);previous=difference;
            check(changed==(strength>0),"Zero-strength stroke adds no edit");
            if(changed){doc.undo();check(Arrays.equals(original,doc.snapshot()),"Whole strength-controlled stroke undoes once");}
        }
        report.append("Stump strokes support zero effect, gentler 35% and full strength with one-step undo.\n");
    }
    private static void sizeRangeChecks(StringBuilder report) {
        ToolSettings settings=ToolSettings.defaults(ToolSettings.Tool.BRUSH).size(72).minimum(20);
        ToneDocument doc=new ToneDocument(256,128);
        PressureStroke brush=new PressureStroke(doc,settings,0);brush.sample(64,64,.05f);brush.finish();
        check(bounds(doc,64,64,40)[0]==20,"Brush light pressure uses configured minimum");
        brush=new PressureStroke(doc,settings,0);brush.sample(192,64,.45f);brush.finish();
        check(bounds(doc,192,64,40)[0]==72,"Brush firm pressure uses configured maximum");
        byte[] black=new byte[256*128];ToneDocument erased=new ToneDocument(256,128,black);
        ToolStroke eraser=new ToolStroke(erased,ToolSettings.defaults(ToolSettings.Tool.ERASER).size(72).minimum(20),0);
        eraser.sample(64,64,.05f,0,0);eraser.finish();
        check(erased.tone(70,64)>0&&erased.tone(75,64)==0,"Eraser minimum affects its actual contact");
        ToneDocument pencilDoc=new ToneDocument(256,128);
        ToolStroke pencil=new ToolStroke(pencilDoc,ToolSettings.defaults(ToolSettings.Tool.PENCIL).size(72).minimum(20).hardness(0),0);
        pencil.sample(64,64,.05f,0,0);pencil.finish();
        check(bounds(pencilDoc,64,64,40)[0]>=18&&bounds(pencilDoc,64,64,40)[0]<=20,"Pencil upright minimum respects contact range");
        report.append("Configured minimum/maximum pressure widths, gradual eraser contact and upright pencil minimum pass.\n");
    }
    private static long edgeEnergy(ToneDocument doc) {
        long sum=0;for(int x=80;x<176;x++){int difference=doc.tone(x+1,64)-doc.tone(x,64);sum+=difference*difference;}return sum;
    }
    private static int[] bounds(ToneDocument doc,int cx,int cy,int radius) {
        int l=cx+radius,r=cx-radius,t=cy+radius,b=cy-radius;
        for(int y=cy-radius;y<cy+radius;y++)for(int x=cx-radius;x<cx+radius;x++)if(doc.tone(x,y)!=255){l=Math.min(l,x);r=Math.max(r,x);t=Math.min(t,y);b=Math.max(b,y);}
        return new int[]{Math.max(0,r-l+1),Math.max(0,b-t+1)};
    }
    private static void tiltEvent(View view,long start,int action,float x,float y,float pressure,float tx,float ty) {
        MotionEvent.PointerProperties properties=new MotionEvent.PointerProperties();properties.id=0;properties.toolType=MotionEvent.TOOL_TYPE_STYLUS;
        MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=x;coords.y=y;coords.pressure=pressure;
        coords.setAxisValue(MotionEvent.AXIS_TILT,tx);coords.orientation=ty;
        MotionEvent event=MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{properties},new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_STYLUS,0);
        view.dispatchTouchEvent(event);event.recycle();
    }
    private static void checkFirmwareArea(Object pad) throws Exception {
        Object controller = field(field(pad,"input"),"controller");
        Object base = field(controller,"mOurViewRecord");
        check((Boolean)field(base,"replaceByUserSet"), "Firmware whole-view default is excluded");
        Object global = field(controller,"mGlobalVirtualView");
        java.util.List<?> records = (java.util.List<?>)field(global,"mPWViewList");
        int owned = 0;
        for (Object record : records) {
            if (field(record,"pwCoreCtrl") == controller && (Boolean)field(record,"isUserSet")) {
                android.graphics.Rect bounds = (android.graphics.Rect)field(record,"winFrame");
                check(bounds.isEmpty(), "Firmware cannot write anywhere in document canvas"); owned++;
            }
        }
        check(owned == 1, "Exactly one empty firmware region across reconnects");
    }
    private static void namedSave(Instrumentation test, ToneDocument doc) throws Exception {
        java.io.File scratch = new java.io.File(test.getTargetContext().getCacheDir(), "document-check-" + System.nanoTime());
        DocumentStore store = new DocumentStore(scratch);
        CountDownLatch done = new CountDownLatch(1);
        final Exception[] error = new Exception[1]; final ToneDocument[] restored = new ToneDocument[1];
        store.save("Test drawing",new DocumentStore.Snapshot(doc),(value,failure) -> error[0]=failure);
        store.open("Test drawing",(value,failure) -> { restored[0]=value; if(failure!=null) error[0]=failure; done.countDown(); });
        check(done.await(10,TimeUnit.SECONDS) && error[0]==null && Arrays.equals(doc.snapshot(),restored[0].snapshot()), "Named save/open exact tones");
        new java.io.File(scratch,"drawings/Test drawing.tsm").delete();
        new java.io.File(scratch,"drawings").delete(); scratch.delete();
    }
    private static void awaitReady(Instrumentation test, PaintActivity activity) throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        while (((Boolean)field(activity, "loading") || !activity.hasWindowFocus()
                || !(Boolean)field(activity,"resumed")) && SystemClock.uptimeMillis() < end) SystemClock.sleep(50);
        test.waitForIdleSync(); SystemClock.sleep(300);
        check(!(Boolean)field(activity,"loading"), "Activity loaded");
        check(activity.hasWindowFocus() && (Boolean)field(activity,"resumed"), "Activity resumed with window focus");
    }
    private static void checkPicker(Instrumentation test, PaintActivity activity, StringBuilder report) throws Exception {
        View picker = (View)field(activity,"shadePicker");
        Bitmap strip = (Bitmap)field(picker,"strip");
        int cap = (Integer)field(picker,"endpointWidth");
        check(picker.getWidth() < ((View)field(activity,"palette")).getWidth() * .7f, "Picker leaves generous side space");
        checkHeader(activity);
        boolean[] densities = new boolean[65];
        for (int x = 0; x < strip.getWidth(); x++) {
            int tone = DotPattern.pickerTone(Math.round((x-cap) * 255f / (strip.getWidth()-2*cap-1)));
            densities[DotPattern.whiteCount(tone)] = true;
            for (int y = 0; y < strip.getHeight(); y++)
                check(strip.getPixel(x,y) == DotPattern.pixel(tone,x,y), "Gradient uses shared calibrated dots");
            if (x < cap) check(tone == 0, "Wide pure black end");
            if (x >= strip.getWidth()-cap) check(tone == 255, "Wide pure white end");
        }
        for (boolean density : densities) check(density, "All densities visible in gradient");
        Object pad = field(activity,"pad");
        byte[] original = ((ToneDocument)field(pad,"document")).snapshot();
        long start = SystemClock.uptimeMillis();
        float inset = (picker.getWidth()-strip.getWidth())/2f;
        test.runOnMainSync(() -> {
            event(picker,start,MotionEvent.ACTION_DOWN,inset+cap/2f,picker.getHeight()/2f,.5f,MotionEvent.TOOL_TYPE_FINGER);
            event(picker,start,MotionEvent.ACTION_UP,inset+cap/2f,picker.getHeight()/2f,0,MotionEvent.TOOL_TYPE_FINGER);
        });
        check((Integer)field(activity,"gray")==0, "Tap broad black end selects pure black");
        test.runOnMainSync(() -> {
            event(picker,start,MotionEvent.ACTION_DOWN,inset+strip.getWidth()-cap/2f,picker.getHeight()/2f,.5f,MotionEvent.TOOL_TYPE_FINGER);
            event(picker,start,MotionEvent.ACTION_UP,inset+strip.getWidth()-cap/2f,picker.getHeight()/2f,0,MotionEvent.TOOL_TYPE_FINGER);
        });
        check((Integer)field(activity,"gray")==255, "Tap broad white end selects pure white");
        float light = inset + cap + .92f * (strip.getWidth()-2*cap-1);
        test.runOnMainSync(() -> {
            event(picker,start,MotionEvent.ACTION_DOWN,picker.getWidth()*.5f,picker.getHeight()/2f,.5f,MotionEvent.TOOL_TYPE_FINGER);
            event(picker,start,MotionEvent.ACTION_MOVE,light,picker.getHeight()/2f,.5f,MotionEvent.TOOL_TYPE_FINGER);
            event(picker,start,MotionEvent.ACTION_UP,light,picker.getHeight()/2f,0,MotionEvent.TOOL_TYPE_FINGER);
        });
        int gray = (Integer)field(activity,"gray");
        check(DotPattern.whiteCount(gray)>50 && DotPattern.whiteCount(gray)<64, "New lighter shades selectable");
        check(Arrays.equals(original,((ToneDocument)field(pad,"document")).snapshot()), "Picker touches never paint");
        View preview = (View)field(activity,"shadePreview");
        Bitmap bitmap = Bitmap.createBitmap(preview.getWidth(),preview.getHeight(),Bitmap.Config.ARGB_8888);
        test.runOnMainSync(() -> preview.draw(new Canvas(bitmap)));
        int white=0, left=bitmap.getWidth()/2-4, top=bitmap.getHeight()/2-4;
        for (int y=top;y<top+8;y++) for (int x=left;x<left+8;x++) if (bitmap.getPixel(x,y)==Color.WHITE) white++;
        check(white==DotPattern.whiteCount(gray), "Selected shade preview matches canvas density"); bitmap.recycle();
        report.append("Shortened dotted picker exposes all 65 densities; wide black/white ends and intermediate shades select without painting.\n");
    }
    private static void rasterBaseline() {
        for (int gray : GrayPalette.VALUES) {
            ToneDocument doc = new ToneDocument(256, 128);
            PressureStroke stroke = new PressureStroke(doc, 64, gray);
            Bitmap expected = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888); expected.eraseColor(Color.WHITE);
            Canvas canvas = new Canvas(expected); Paint paint = new Paint(); paint.setColor(Color.rgb(gray,gray,gray));
            float[] xs = {-.5f, 20.3f, 63.9f, 97.5f, 143.1f, 200.9f, 255.5f};
            float[] ys = {5.8f, 30.1f, 50.6f, 63.5f, 62.7f, 89.2f, 125.7f};
            float[] ps = {.1f, .2f, .3f, .45f, .4f, .15f, .1f};
            float px = xs[0], py = ys[0], pr = NativePen.diameter(64,ps[0],0,0,false)/2;
            for (int n = 0; n < xs.length; n++) {
                stroke.sample(xs[n],ys[n],ps[n]);
                float r = NativePen.diameter(64,ps[n],0,0,false)/2;
                int steps = Math.max(1,(int)Math.ceil(Math.hypot(xs[n]-px,ys[n]-py)/Math.max(.5f,Math.min(r,pr)*.4f)));
                for (int s = 1; s <= steps; s++) {
                    float t = (float)s/steps;
                    canvas.drawCircle(px+(xs[n]-px)*t,py+(ys[n]-py)*t,pr+(r-pr)*t,paint);
                }
                px=xs[n]; py=ys[n]; pr=r;
            }
            stroke.finish();
            for (int y = 0; y < 128; y++) for (int x = 0; x < 256; x++)
                check(doc.tone(x,y) == Color.red(expected.getPixel(x,y)), "0.12 circle raster parity at " + x + "," + y);
            expected.recycle();
        }
    }
    private static void event(View view, long start, int action, float x, float y, float pressure) {
        event(view,start,action,x,y,pressure,MotionEvent.TOOL_TYPE_STYLUS);
    }
    private static void event(View view, long start, int action, float x, float y, float pressure, int tool) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties(); properties.id=0; properties.toolType=tool;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords(); coords.x=x; coords.y=y; coords.pressure=pressure;
        MotionEvent event = MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,1,
                new MotionEvent.PointerProperties[]{properties},new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,
                tool == MotionEvent.TOOL_TYPE_STYLUS ? InputDevice.SOURCE_STYLUS : InputDevice.SOURCE_TOUCHSCREEN,0);
        view.dispatchTouchEvent(event); event.recycle();
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, String name, Object value) {
        try { Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner,value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static Object call(Object owner, String name, Class<?>[] signature, Object... args) {
        try { Method method=owner.getClass().getDeclaredMethod(name,signature); method.setAccessible(true); return method.invoke(owner,args); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static Button findButton(PaintActivity activity, String text) {
        return findButton(activity.getWindow().getDecorView(),text);
    }
    private static Button findButton(View view, String text) {
        if (view instanceof Button && (((Button)view).getText().toString().equals(text)||text.equals(view.getTag()))) return (Button)view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button found=findButton(((ViewGroup)view).getChildAt(i),text); if (found!=null) return found;
        }
        return null;
    }
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
}
