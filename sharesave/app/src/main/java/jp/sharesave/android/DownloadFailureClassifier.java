package jp.sharesave.android;

import java.util.List;
import java.util.Locale;

/** Separates ordinary retryable transport failures from permission restrictions.
 * It does not attempt to work around DRM or access controls.
 */
final class DownloadFailureClassifier {
    private DownloadFailureClassifier(){}
    static final class Result {
        final String category;
        final String guidance;
        Result(String category,String guidance){
            this.category=category;this.guidance=guidance;
        }
    }
    static Result evaluate(String exception,List<String> lines){
        StringBuilder b=new StringBuilder();
        if(exception!=null)b.append(exception).append(' ');
        if(lines!=null)for(String s:lines)if(s!=null)b.append(s).append(' ');
        String log=b.toString().toLowerCase(Locale.ROOT);
        if(log.contains("i/o operation on closed file")){
            return new Result("yt-dlp旧版の既知の入出力不具合",
                "yt-dlp 2025.11.12に同じ不具合があります。アプリに同梱した修正版2026.08.19が利用されているか、エラー詳細のエンジンバージョンを確認してください。");
        }
        if(log.contains("http error 401")||log.matches("(?s).*http 401\\b.*"))
            return new Result("HTTP 401・認証が必要",
                "ログイン状態と閲覧権限を確認してください。アクセス権限のない動画は保存できません。");
        if(log.contains("http error 403")||log.matches("(?s).*http 403\\b.*"))
            return new Result("HTTP 403・サーバーが取得を拒否",
                "配信URLの期限切れ、認証条件、サイト側の取得制限などが考えられます。ページ更新後に再生し、配信候補を新しく取得してから試してください。");
        if(log.contains("http error 404")||log.matches("(?s).*http 404\\b.*"))
            return new Result("HTTP 404・配信URLが見つかりません",
                "配信URLが古くなった可能性があります。ページを再読み込みして再生し直してください。");
        if(log.contains("http error 429")||log.matches("(?s).*http 429\\b.*"))
            return new Result("HTTP 429・アクセス頻度制限",
                "短時間の要求数が多い可能性があります。連続再試行せず、時間を空けてから試してください。");
        if(log.contains("http error 503")||log.contains("http error 502")||log.contains("http error 500"))
            return new Result("サーバーの一時的なエラー",
                "しばらく時間を空けて再度お試しください。");
        if(log.contains("timed out")||log.contains("timeout")||log.contains("4分以上")||
           log.contains("応答しませんでした"))
            return new Result("通信または動画取得処理のタイムアウト",
                "通信状態を確認し、ページ更新後に別の配信候補があれば試してください。");
        if(log.contains("drm")||log.contains("widevine"))
            return new Result("DRM・権利保護された動画",
                "DRM保護を解除する機能はありません。提供サイトの正式な保存機能をご利用ください。");
        return new Result("原因未判定",
            "エラー詳細をコピーし、通信エラーコードと停止段階を確認してください。");
    }
}
