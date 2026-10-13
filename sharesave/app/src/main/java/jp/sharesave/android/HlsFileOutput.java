package jp.sharesave.android;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Keep HLS transport streams in their native container instead of requiring
 * a second full-size MP4 remux and another 2+ GiB of working storage.
 */
final class HlsFileOutput {
    private HlsFileOutput(){}
    static boolean looksLikeMpegTs(File file) throws IOException{
        if(file==null||!file.isFile()||file.length()<3L*188)return false;
        try(FileInputStream input=new FileInputStream(file)){
            byte[] head=new byte[3*192+16];
            int length=input.read(head);
            if(length<3*188)return false;
            for(int offset: new int[]{0,4}){
                int stride=offset==4?192:188;
                if(length>offset+stride*2 && (head[offset]&0xff)==0x47
                    && (head[offset+stride]&0xff)==0x47
                    && (head[offset+stride*2]&0xff)==0x47)return true;
            }
            return false;
        }
    }
    /** Find the completed original TS after a failed FFmpeg FixupM3u8 stage. */
    static File findOriginalTransport(File dir) throws IOException{
        if(dir==null||!dir.isDirectory())return null;
        File[] files=dir.listFiles();
        if(files==null)return null;
        File best=null;
        for(File candidate:files){
            String n=candidate.getName().toLowerCase(Locale.ROOT);
            if(!candidate.isFile()||candidate.length()<1024*1024L)continue;
            if(!n.endsWith(".mp4")&&!n.endsWith(".ts"))continue;
            if(!looksLikeMpegTs(candidate))continue;
            if(best==null||candidate.length()>best.length())best=candidate;
        }
        return best;
    }
    static File correctExtension(File file) throws IOException{
        if(file==null||!file.isFile())throw new IOException("保存対象ファイルがありません");
        String name=file.getName().toLowerCase(Locale.ROOT);
        if(!name.endsWith(".mp4")&&!name.endsWith(".m4v"))return file;
        if(!looksLikeMpegTs(file))return file;
        int dot=file.getName().lastIndexOf('.');
        File target=new File(file.getParentFile(),file.getName().substring(0,dot)+".ts");
        Files.move(file.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
