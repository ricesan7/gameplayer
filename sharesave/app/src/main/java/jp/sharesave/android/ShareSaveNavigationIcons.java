package jp.sharesave.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.Button;
import java.io.InputStream;

/**
 * Navigation icons extracted directly from the user's approved 5-by-2 artwork.
 * Row 0: muted, row 1: neon selected; five columns match bottom navigation.
 * Source: assets/navigation/nav_icons_sprite.png (56 px per state/icon).
 */
final class ShareSaveNavigationIcons {
    private static final int CELL_PX=56;
    private final Context context;
    private final Bitmap sprite;
    private final Drawable[][] icons=new Drawable[2][5];

    ShareSaveNavigationIcons(Context context){
        this.context=context;
        Bitmap decoded=null;
        try(InputStream in=context.getAssets().open("navigation/nav_icons_sprite.png")){
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inScaled=false;
            decoded=BitmapFactory.decodeStream(in,null,options);
        }catch(Exception ignored){}
        if(decoded!=null && (decoded.getWidth()!=CELL_PX*5 ||
                              decoded.getHeight()!=CELL_PX*2)){
            decoded.recycle();
            decoded=null;
        }
        sprite=decoded;
    }
    private Drawable icon(int position,boolean selected){
        if(sprite==null||position<0||position>=5)return null;
        final int state=selected?1:0;
        Drawable cached=icons[state][position];
        if(cached==null){
            Bitmap cropped=Bitmap.createBitmap(sprite,position*CELL_PX,
                state*CELL_PX,CELL_PX,CELL_PX);
            cached=new BitmapDrawable(context.getResources(),cropped);
            cached.setFilterBitmap(true);
            icons[state][position]=cached;
        }
        // Explicit Android dp bounds, rather than 56 pixel intrinsic size.
        final int size=ShareSaveTheme.dp(context,40);
        cached.setBounds(0,0,size,size);
        return cached;
    }
    void apply(Button nav,int index,boolean selected){
        if(nav==null)return;
        ShareSaveTheme.setTab(nav,selected);
        nav.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        nav.setGravity(Gravity.CENTER);
        nav.setAllCaps(false);
        nav.setIncludeFontPadding(false);
        nav.setTextSize(10);
        nav.setCompoundDrawablePadding(0);
        nav.setPadding(0,0,0,0);
        Drawable image=icon(index,selected);
        nav.setCompoundDrawables(null,image,null,null);
        nav.setContentDescription(nav.getText()+ (selected?"：選択中":""));
    }
}
