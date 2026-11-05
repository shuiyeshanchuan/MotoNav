package android.util;
import java.io.*;
import java.nio.file.*;
public final class AtomicFile {
    private final File file,newFile;public static String failName="";
    public AtomicFile(File file){this.file=file;newFile=new File(file.getPath()+".new");}
    public FileOutputStream startWrite()throws IOException{if(!failName.isEmpty()&&file.getName().equals(failName))throw new IOException("simulated atomic storage failure");File p=file.getParentFile();if(!p.isDirectory()&&!p.mkdirs())throw new IOException("mkdir");return new FileOutputStream(newFile);}
    public void finishWrite(FileOutputStream stream)throws IOException{stream.getFD().sync();stream.close();Files.move(newFile.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    public void failWrite(FileOutputStream stream){try{stream.close();Files.deleteIfExists(newFile.toPath());}catch(IOException ignored){}}
    public FileInputStream openRead()throws FileNotFoundException{return new FileInputStream(file);}
}
