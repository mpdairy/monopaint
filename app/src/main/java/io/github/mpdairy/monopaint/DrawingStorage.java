package io.github.mpdairy.monopaint;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import java.io.File;

/**
 * Where the drawing library lives. Drawings belong in shared storage, where they outlive
 * uninstalling or reinstalling the app (including with a different signing key), and a
 * reinstalled app finds them again: Document/MonoPaint on a Supernote, whose Files app shows
 * its own Document folder rather than Android's Documents, and the standard
 * Documents/MonoPaint on other tablets. Until the app may write there, drawings stay in its
 * private files.
 */
final class DrawingStorage {
    static final String SHARED_NAME = (Device.supernote() ? "Document" : Environment.DIRECTORY_DOCUMENTS) + "/" + BuildConfig.LIBRARY_FOLDER;

    static File shared() { return new File(Environment.getExternalStorageDirectory(), SHARED_NAME); }
    static File appPrivate(Context context) { return new File(context.getFilesDir(), "drawings"); }

    static boolean sharedAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** The library in use now. */
    static File library(Context context) { return sharedAllowed(context) ? shared() : appPrivate(context); }

    /**
     * Asks Android for shared storage: the "All files access" switch on Android 11 and later,
     * otherwise the storage permission. The answer comes back to the activity's
     * {@code onActivityResult} or {@code onRequestPermissionsResult} with {@code request};
     * returns false if this device cannot ask.
     */
    static boolean request(Activity activity, int request) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, request);
            return true;
        }
        Intent app = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + activity.getPackageName()));
        for (Intent intent : new Intent[]{app, new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)}) {
            try { activity.startActivityForResult(intent, request); return true; }
            catch (ActivityNotFoundException ignored) { }
        }
        return false;
    }

    private DrawingStorage() {}
}
