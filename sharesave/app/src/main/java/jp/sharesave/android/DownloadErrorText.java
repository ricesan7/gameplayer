package jp.sharesave.android;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Summarize stderr without allowing initial outdated-version warnings to mask a real error. */
final class DownloadErrorText {
    private DownloadErrorText(){}
    private static final Pattern URL=Pattern.compile("https?://[^\\s\\]\)<>\\\"']+",Pattern.CASE_INSENSITIVE);
    private static final Pattern COOKIE=Pattern.compile("(?im)(cookie|authorization)\\s*[:=]\\s*[^\\r\\n]+");
    static String scrub(String input){
        if(input==null)return "";
        String result=COOKIE.matcher(input).replaceAll("$1: [非表示]");
        result=URL.matcher(result).replaceAll("[URL]");
        return result.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"," ").trim();
    }
    static boolean isWarning(String text){
        String lower=text.toLowerCase(Locale.ROOT);
        return lower.startsWith("warning:") || lower.contains("older than 90 days") ||
            lower.contains("strongly recommended to always use the latest version") ||
            lower.contains("to suppress this warning");
    }
    static String select(String exceptionMessage,List<String> lines){
        String choice="";
        if(lines!=null){
            for(int i=lines.size()-1;i>=0;i--){
                String value=scrub(lines.get(i));
                if(value.isEmpty()||isWarning(value))continue;
                if(value.startsWith("ERROR:")||value.startsWith("error:")||
                    value.contains("HTTP Error ")||value.contains("Unsupported URL")||
                    value.contains("Unable to download")||value.contains("Failed to")){
                    choice=value;
                    break;
                }
            }
        }
        if(choice.isEmpty()){
            String msg=scrub(exceptionMessage);
            if(!msg.isEmpty()){
                for(String line:msg.split("[\\r\\n]+")){
                    String v=line.trim();
                    if(!v.isEmpty()&&!isWarning(v) &&
                        (v.contains("ERROR:")||v.contains("HTTP Error")||v.contains("Unsupported URL")))
                        choice=v;
                }
                if(choice.isEmpty()){
                    for(String line:msg.split("[\\r\\n]+")){
                        String v=line.trim();
                        if(!v.isEmpty()&&!isWarning(v))choice=v;
                    }
                }
            }
        }
        if(choice.isEmpty())choice="原因を特定できませんでした。エラー詳細をご確認ください。";
        if(choice.length()>230)choice=choice.substring(0,230)+"…";
        return choice;
    }
    static String details(String exceptionMessage,List<String> lines){
        StringBuilder out=new StringBuilder();
        if(lines!=null){
            int start=Math.max(0,lines.size()-35);
            for(int i=start;i<lines.size();i++){
                String line=scrub(lines.get(i));
                if(!line.isEmpty())out.append(line).append('\n');
            }
        }
        if(exceptionMessage!=null&&!exceptionMessage.trim().isEmpty())
            out.append("\nException: ").append(scrub(exceptionMessage));
        if(out.length()==0)return "追加ログなし";
        if(out.length()>6500)return out.substring(out.length()-6500);
        return out.toString();
    }
}
