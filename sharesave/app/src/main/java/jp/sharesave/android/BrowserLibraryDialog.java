package jp.sharesave.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class BrowserLibraryDialog {
    private final Activity activity;
    private final BrowserStore store;
    private final Consumer<String> open;
    private final Runnable changed;
    BrowserLibraryDialog(Activity activity,BrowserStore store,Consumer<String> open,Runnable changed){
        this.activity=activity;this.store=store;this.open=open;this.changed=changed;
    }
    private int dp(int n){return ShareSaveTheme.dp(activity,n);}
    private LinearLayout root(){
        LinearLayout layout=new LinearLayout(activity);
        layout.setPadding(dp(12),dp(8),dp(12),dp(12));
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(ShareSaveTheme.BG);
        return layout;
    }
    private EditText editor(String value,String hint){
        EditText e=new EditText(activity);
        e.setSingleLine(true);
        e.setTextColor(ShareSaveTheme.WHITE);
        e.setHintTextColor(ShareSaveTheme.MUTED);
        e.setHint(hint);e.setText(value==null?"":value);
        e.setTextSize(14);
        e.setPadding(dp(12),0,dp(12),0);
        e.setBackground(ShareSaveTheme.input(activity));
        return e;
    }
    void saveCurrent(String url,String title){
        if(!BrowserUrlRules.valid(url)){
            message("ブックマーク","保存できるHTTPSページを開いてください。");return;
        }
        EditText input=editor(BrowserUrlRules.title(title,url),"ブックマーク名");
        LinearLayout container=root();
        TextView link=ShareSaveTheme.text(activity,url,11,false,ShareSaveTheme.SECONDARY);
        link.setMaxLines(2);
        container.addView(link,ShareSaveTheme.bottomMargin(activity,10));
        container.addView(input,new LinearLayout.LayoutParams(-1,dp(47)));
        new AlertDialog.Builder(activity).setTitle("☆ ブックマークに登録")
            .setView(container).setNegativeButton("キャンセル",null)
            .setPositiveButton("保存",(d,w)->{
                store.addBookmark(url,input.getText().toString());changed.run();
            }).show();
    }
    void showBookmarks(){show(false);}
    void showHistory(){show(true);}
    private void message(String title,String content){
        new AlertDialog.Builder(activity).setTitle(title).setMessage(content)
            .setPositiveButton("OK",null).show();
    }
    private void edit(BrowserStore.Record item,Runnable refresh){
        EditText name=editor(item.title,"表示名");
        LinearLayout container=root();
        container.addView(name,new LinearLayout.LayoutParams(-1,dp(48)));
        new AlertDialog.Builder(activity).setTitle("ブックマーク名を編集")
            .setView(container).setNegativeButton("キャンセル",null)
            .setPositiveButton("保存",(d,w)->{
                store.addBookmark(item.url,name.getText().toString());
                changed.run();refresh.run();
            }).show();
    }
    private void show(boolean history){
        LinearLayout contents=root();
        EditText search=editor("","タイトル・URLで検索");
        contents.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));
        TextView count=ShareSaveTheme.text(activity,"",12,false,ShareSaveTheme.SECONDARY);
        count.setPadding(0,dp(10),0,dp(7));
        contents.addView(count);
        ScrollView scroll=new ScrollView(activity);
        scroll.setFillViewport(false);
        LinearLayout list=new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        contents.addView(scroll,new LinearLayout.LayoutParams(-1,dp(390)));
        final AlertDialog[] dialog={null};
        Runnable[] refresh={null};
        refresh[0]=()->{
            list.removeAllViews();
            List<BrowserStore.Record> all=history?store.history():store.bookmarks();
            String filter=search.getText().toString().trim().toLowerCase(Locale.ROOT);
            int visible=0;
            for(BrowserStore.Record item:all){
                if(!filter.isEmpty() &&
                  !(item.title+" "+item.url).toLowerCase(Locale.ROOT).contains(filter))continue;
                visible++;
                LinearLayout row=ShareSaveTheme.card(activity);
                LinearLayout.LayoutParams rowParams=ShareSaveTheme.bottomMargin(activity,7);
                TextView headline=ShareSaveTheme.text(activity,item.title,14,true,ShareSaveTheme.WHITE);
                headline.setMaxLines(2);
                row.addView(headline);
                TextView link=ShareSaveTheme.text(activity,item.url,11,false,ShareSaveTheme.CYAN);
                link.setMaxLines(2);row.addView(link);
                if(history){
                    TextView date=ShareSaveTheme.text(activity,
                      DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,Locale.JAPAN)
                          .format(new Date(item.time)),10,false,ShareSaveTheme.MUTED);
                    row.addView(date);
                }
                LinearLayout actions=new LinearLayout(activity);
                actions.setGravity(Gravity.END);
                Button go=ShareSaveTheme.button(activity,"開く",true);
                go.setOnClickListener(v->{
                    if(dialog[0]!=null)dialog[0].dismiss();
                    open.accept(item.url);
                });
                actions.addView(go,new LinearLayout.LayoutParams(dp(85),dp(41)));
                if(!history){
                    Button rename=ShareSaveTheme.button(activity,"編集",false);
                    LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(70),dp(41));
                    rp.leftMargin=dp(7);
                    rename.setOnClickListener(v->edit(item,refresh[0]));
                    actions.addView(rename,rp);
                    Button remove=ShareSaveTheme.button(activity,"削除",false);
                    LinearLayout.LayoutParams dp2=new LinearLayout.LayoutParams(dp(70),dp(41));
                    dp2.leftMargin=dp(7);
                    remove.setTextColor(ShareSaveTheme.RED);
                    remove.setOnClickListener(v->new AlertDialog.Builder(activity)
                       .setTitle("ブックマークを削除")
                       .setMessage("「"+item.title+"」を削除しますか？")
                       .setNegativeButton("キャンセル",null)
                       .setPositiveButton("削除",(d,w)->{
                           store.removeBookmark(item.url);changed.run();refresh[0].run();
                       }).show());
                    actions.addView(remove,dp2);
                }
                LinearLayout.LayoutParams actionParams=ShareSaveTheme.bottomMargin(activity,0);
                actionParams.topMargin=dp(9);
                row.addView(actions,actionParams);
                list.addView(row,rowParams);
            }
            count.setText((history?"閲覧履歴":"ブックマーク")+" "+all.size()+"件 / 表示 "+visible+"件");
            if(visible==0){
                TextView empty=ShareSaveTheme.text(activity,
                    history?"履歴はありません":"ブックマークはありません",13,false,ShareSaveTheme.MUTED);
                empty.setGravity(Gravity.CENTER);
                list.addView(empty,new LinearLayout.LayoutParams(-1,dp(80)));
            }
        };
        search.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                refresh[0].run();
            }
            @Override public void afterTextChanged(Editable e){}
        });
        AlertDialog.Builder builder=new AlertDialog.Builder(activity)
            .setTitle(history?"閲覧履歴":"ブックマーク")
            .setView(contents)
            .setNegativeButton("閉じる",null);
        if(history)builder.setNeutralButton("履歴を削除",(d,w)->
            new AlertDialog.Builder(activity).setTitle("閲覧履歴をすべて削除")
               .setMessage("アプリ内に保存されたURLの閲覧履歴を削除します。ブックマークは残ります。")
               .setNegativeButton("キャンセル",null)
               .setPositiveButton("削除",(dialog2,which)->{
                   store.clearHistory();changed.run();
               }).show());
        dialog[0]=builder.create();
        refresh[0].run();
        dialog[0].show();
    }
}
