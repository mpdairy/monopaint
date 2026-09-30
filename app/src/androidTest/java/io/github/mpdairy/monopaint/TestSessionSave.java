package io.github.mpdairy.monopaint;

import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Instrumentation kills its target process on finish; drain restored session writes first. */
final class TestSessionSave {
    static void await(PaintActivity activity) throws Exception {
        Field queue=DocumentStore.class.getDeclaredField("IO");queue.setAccessible(true);
        ((ExecutorService)queue.get(null)).submit(() -> {}).get(30,TimeUnit.SECONDS);
        Field prefs=PaintActivity.class.getDeclaredField("preferences");prefs.setAccessible(true);
        if(!((SharedPreferences)prefs.get(activity)).edit().commit())
            throw new IllegalStateException("Restored test session settings were not saved");
    }
    private TestSessionSave() {}
}
