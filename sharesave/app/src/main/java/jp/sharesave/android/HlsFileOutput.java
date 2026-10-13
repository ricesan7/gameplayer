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
