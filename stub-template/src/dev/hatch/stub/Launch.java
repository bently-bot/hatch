package dev.hatch.stub;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

/** Tiny trampoline: read hatch.appId meta-data and hand off to the host WindowActivity. */
public class Launch extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String appId = "unknown";
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            if (ai.metaData != null) appId = ai.metaData.getString("hatch.appId", appId);
        } catch (Exception ignored) {}
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("hatch://app/" + appId));
        i.setClassName("dev.hatch", "dev.hatch.ui.WindowActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra("appId", appId);
        try { startActivity(i); } catch (Exception e) {
            // Host not installed
        }
        finish();
    }
}
