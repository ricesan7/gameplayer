package jp.sharesave.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;

/** Ensures content never overlaps status-bar icons, cutouts or navigation controls.
 * The window lays out edge-to-edge on every supported API, and the insets are
 * applied exactly once as root padding (not hard-coded status bar dimensions).
 */
final class SafeWindowLayout {
    private SafeWindowLayout(){}
    static void apply(Activity activity,View root,boolean lightSystemIcons){
        Window w=activity.getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if(Build.VERSION.SDK_INT>=29){
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if(!lightSystemIcons){
            flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|
                   View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
        final int l=root.getPaddingLeft(),t=root.getPaddingTop(),
                  r=root.getPaddingRight(),b=root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int left,top,right,bottom;
            if(Build.VERSION.SDK_INT>=30){
                Insets safe=insets.getInsets(WindowInsets.Type.statusBars()|
                        WindowInsets.Type.navigationBars()|
                        WindowInsets.Type.displayCutout());
                left=safe.left;top=safe.top;right=safe.right;bottom=safe.bottom;
            }else{
                left=insets.getSystemWindowInsetLeft();
                top=insets.getSystemWindowInsetTop();
                right=insets.getSystemWindowInsetRight();
                bottom=insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l+left,t+top,r+right,b+bottom);
            return insets;
        });
        root.requestApplyInsets();
    }
    static void immersive(Activity activity,boolean enabled,boolean lightSystemIcons){
        int flags=View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if(enabled){
            flags|=View.SYSTEM_UI_FLAG_FULLSCREEN|
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }else if(!lightSystemIcons){
            flags|=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|
                   View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        View root=activity.findViewById(android.R.id.content);
        if(root!=null)root.requestApplyInsets();
    }
}
