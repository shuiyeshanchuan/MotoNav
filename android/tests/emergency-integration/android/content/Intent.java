package android.content;
import android.net.Uri;
import java.util.HashMap;
import java.util.Map;
public class Intent {
    public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000,FLAG_ACTIVITY_SINGLE_TOP=0x20000000;
    private String action;private Uri data;public Class<?> target;public final Map<String,Object> extras=new HashMap<>();
    public Intent(){}public Intent(Context c,Class<?> target){this.target=target;}
    public Intent setAction(String action){this.action=action;return this;}public String getAction(){return action;}public Intent setData(Uri data){this.data=data;return this;}public Uri getData(){return data;}
    public Intent addFlags(int flags){return this;}
    public Intent putExtra(String key,long v){extras.put(key,v);return this;}public Intent putExtra(String key,int v){extras.put(key,v);return this;}public Intent putExtra(String key,boolean v){extras.put(key,v);return this;}public Intent putExtra(String key,byte[] v){extras.put(key,v);return this;}public Intent putExtra(String key,String v){extras.put(key,v);return this;}
    public long getLongExtra(String key,long def){Object v=extras.get(key);return v instanceof Number?((Number)v).longValue():def;}public int getIntExtra(String key,int def){Object v=extras.get(key);return v instanceof Number?((Number)v).intValue():def;}
    public boolean getBooleanExtra(String key,boolean def){Object v=extras.get(key);return v instanceof Boolean?(Boolean)v:def;}public byte[] getByteArrayExtra(String key){return(byte[])extras.get(key);}public String getStringExtra(String key){return(String)extras.get(key);}
}
