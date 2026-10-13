package jp.sharesave.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ShareSave visual system v1.0. Native drawables and live widgets; no
 * screenshots baked into the UI and no false PRO/premium entitlement badges.
 */
final class ShareSaveTheme {
    static final int BG=Color.rgb(5,10,20);
    static final int SUB_BG=Color.rgb(11,22,36);
    static final int CARD=Color.rgb(17,27,43);
    static final int BORDER=Color.rgb(36,66,96);
    static final int WHITE=Color.WHITE;
    static final int SECONDARY=Color.rgb(156,177,201);
    static final int MUTED=Color.rgb(107,124,147);
    static final int BLUE=Color.rgb(0,163,255);
    static final int CYAN=Color.rgb(0,229,255);
    static final int GREEN=Color.rgb(0,214,143);
    static final int ORANGE=Color.rgb(255,159,10);
    static final int RED=Color.rgb(255,71,87);
    static final int GOLD=Color.rgb(255,211,77);
    private ShareSaveTheme(){}
    static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable panel(Context c,boolean selected){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            selected?new int[]{0xff142F50,CARD}:new int[]{0xff15263C,SUB_BG});
        d.setCornerRadius(dp(c,15)); d.setStroke(dp(c,1),selected?BLUE:BORDER);
        return d;
    }
    static GradientDrawable input(Context c){
        GradientDrawable d=new GradientDrawable();
        d.setColor(0xff0a1828);d.setCornerRadius(dp(c,12));
        d.setStroke(dp(c,1),0xff2b537a);return d;
    }
    static GradientDrawable buttonBackground(Context c,boolean primary){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            primary?new int[]{CYAN,0xff0066FF}:new int[]{0xff1a314c,0xff102034});
        d.setCornerRadius(dp(c,11));
        if(!primary)d.setStroke(dp(c,1),0xff345d83);
        return d;
    }
    static Button button(Context c,String label,boolean primary){
        Button b=new Button(c);
        b.setText(label);b.setTextSize(13);b.setAllCaps(false);
        b.setTextColor(primary?0xff031421:WHITE);
        b.setTypeface(null,android.graphics.Typeface.BOLD);
        b.setMinHeight(0);b.setMinimumHeight(0);
        b.setPadding(dp(c,8),0,dp(c,8),0);
        b.setBackground(buttonBackground(c,primary));
        return b;
    }
    static TextView text(Context c,String label,int size,boolean strong,int color){
        TextView t=new TextView(c);
        t.setText(label);t.setTextSize(size);t.setTextColor(color);
        if(strong)t.setTypeface(null,android.graphics.Typeface.BOLD);
        return t;
    }
    static LinearLayout card(Context c){
        LinearLayout card=new LinearLayout(c);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(c,14),dp(c,14),dp(c,14),dp(c,14));
        card.setBackground(panel(c,false));return card;
    }
    static LinearLayout.LayoutParams bottomMargin(Context c,int margin){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.bottomMargin=dp(c,margin);
        return lp;
    }
    static void setTab(Button button,boolean active){
        button.setTextColor(active?CYAN:SECONDARY);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setTypeface(null,active?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);
    }
    static void tintProgress(android.widget.ProgressBar bar){
        bar.setProgressTintList(ColorStateList.valueOf(CYAN));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff223653));
        bar.setIndeterminateTintList(ColorStateList.valueOf(CYAN));
    }
}
