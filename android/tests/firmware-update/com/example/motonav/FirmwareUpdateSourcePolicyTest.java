package com.example.motonav;

import org.json.JSONArray;
import org.json.JSONObject;

/** Offline policy regression against the actual compiled Release FirmwareUpdateClient. No GitHub requests. */
public final class FirmwareUpdateSourcePolicyTest {
    private static final FirmwareUpdateClient CLIENT=FirmwareUpdateClient.INSTANCE;
    private static final String ROOT="https://github.com/shuiyeshanchuan/MotoNav/releases/download/";
    private static final String API="https://api.github.com/repos/shuiyeshanchuan/MotoNav/releases/assets/";
    private static int checks;
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message);checks++; }
    private static JSONObject item(String board,String version,String channel,String tag) {
        return new JSONObject().put("board",board).put("version",version).put("channel",channel)
                .put("manifestUrl",ROOT+tag+"/firmware_manifest.json").put("imageUrl",ROOT+tag+"/firmware_app_0x10000.bin")
                .put("manifestApiUrl",API+"101").put("imageApiUrl",API+"102")
                .put("releaseName","release "+tag).put("notes","test only").put("prerelease",false);
    }
    private static JSONObject feed(JSONObject... items) { JSONArray list=new JSONArray();for(JSONObject item:items)list.put(item);return new JSONObject().put("schema",1).put("firmwares",list); }
    private static FirmwareUpdateClient.Candidate select(JSONObject... items) { return CLIENT.selectFeedCandidate(feed(items),"175C",false); }
    private static void fails(String expected,Runnable operation) {
        try { operation.run();throw new AssertionError("expected failure: "+expected); }
        catch(IllegalStateException|IllegalArgumentException e) { check(e.getMessage().contains(expected),"specific failure retained: "+e.getMessage());check(!e.getMessage().contains("暂未发布"),"network error is not reported as unpublished firmware"); }
    }
    private static JSONObject asset(String name,long id,String tag) { return new JSONObject().put("name",name).put("id",id).put("browser_download_url",ROOT+tag+"/"+name); }
    private static JSONObject release(String tag,String published,long id,boolean diagnostic) {
        String index=diagnostic?"motonav-ota-diagnostic.json":"motonav-ota-stable.json";
        JSONArray assets=new JSONArray().put(asset(index,id,tag)).put(asset("firmware_manifest.json",id+1,tag)).put(asset("firmware_app_0x10000.bin",id+2,tag));
        return new JSONObject().put("tag_name",tag).put("name",tag).put("published_at",published).put("prerelease",false).put("draft",false).put("assets",assets);
    }
    private static JSONObject index(String version,boolean diagnostic) {
        return new JSONObject().put("schema",1).put("channel",diagnostic?"diagnostic":"stable").put("firmwares",new JSONArray().put(new JSONObject()
                .put("board","175C").put("version",version).put("manifestAsset","firmware_manifest.json").put("imageAsset","firmware_app_0x10000.bin")));
    }
    public static void main(String[] args) {
        JSONObject diagnostic=item("175C","v7.12.9-nav.16-ride-info-imu-test","diagnostic","v8.3.68");
        JSONObject stable=item("175C","v7.12.9-nav.6","stable","v8.3.39");
        JSONObject power=item("175C","v7.12.9-power.18","stable","v8.3.30");
        check(CLIENT.selectFeedCandidate(feed(diagnostic,stable,power),"175C",true).getDiagnostic(),"explicit diagnostic path retains newest publication ahead of incomparable old power labels");
        check(select(diagnostic,stable,power).getVersion().equals("v7.12.9-nav.6"),"ordinary stable path excludes newer diagnostic");
        fails("稳定更新目录",()->select(item("175C","v7.12.9-nav.99-imu-test","stable","tag")));
        check(select(item("175C","v7.12.9-nav.6","stable","tag").put("prerelease",true))==null,"prerelease stable feed entry is blocked");
        check(CLIENT.selectFeedCandidate(feed(diagnostic),"175",true)==null,"175C feed cannot update legacy 175 board");
        for(String url:new String[]{"https://evil.example/firmware_manifest.json",ROOT+"other/firmware_manifest.json?token=x",ROOT+"other/firmware_manifest.json#x",
                "https://github.com.evil.example/shuiyeshanchuan/MotoNav/releases/download/tag/firmware_manifest.json",ROOT+"../firmware_manifest.json"}) {
            fails("更新目录",()->select(item("175C","v7.12.9-nav.6","stable","tag").put("manifestUrl",url)));
        }
        fails("更新目录",()->select(item("175C","v7.12.9-nav.6","stable","tag").put("manifestApiUrl","https://api.github.com/repos/other/repo/releases/assets/1")));
        FirmwareUpdateClient.Candidate compatible=new FirmwareUpdateClient.Candidate("175C","v1","m","i","r","n",false);
        check(compatible.getManifestApiUrl().isEmpty()&&compatible.getImageApiUrl().isEmpty(),"existing seven-argument candidate constructor remains valid");

        GitHubUpdateSource.reset();GitHubUpdateSource.feed=feed(diagnostic,stable,power);
        FirmwareUpdateClient.Candidate chosen=CLIENT.check("175C",true);
        check(chosen.getVersion().contains("nav.16") && chosen.getImageApiUrl().equals(API+"102"),"feed candidate keeps full direct and API URLs");
        check(GitHubUpdateSource.requests==1 && GitHubUpdateSource.releaseRequests==0,"valid small feed needs one metadata request and no release scan");
        GitHubUpdateSource.reset();GitHubUpdateSource.feed=feed(stable,item("175C","v7.12.9-nav.17","stable","bad").put("imageUrl","https://untrusted.example/image.bin"));
        GitHubUpdateSource.releases=new JSONArray().put(release("verified","2026-11-08T00:00:00Z",15,false));GitHubUpdateSource.indexes.put(API+"15",index("v7.12.9-nav.18",false));
        check(CLIENT.check("175C",false).getVersion().endsWith("nav.18"),"malformed matching feed entry falls back instead of returning an older valid entry");

        GitHubUpdateSource.reset();GitHubUpdateSource.feedError="raw source HTTP 504";
        GitHubUpdateSource.releases=new JSONArray().put(release("old","2026-01-01T00:00:00Z",10,false)).put(release("new","2026-11-08T00:00:00Z",20,true));
        GitHubUpdateSource.indexes.put(API+"10",index("v7.12.9-power.18",false));GitHubUpdateSource.indexes.put(API+"20",index("v7.12.9-nav.16-imu-test",true));
        chosen=CLIENT.check("175C",true);
        check(chosen.getVersion().contains("nav.16") && chosen.getDiagnostic(),"bounded API fallback uses publication order, then existing version policy");
        check(GitHubUpdateSource.requestedIndexes.get(0).equals(API+"20"),"index downloads use faster official API asset endpoint first");
        check(chosen.getManifestApiUrl().equals(API+"21") && chosen.getImageApiUrl().equals(API+"22"),"fallback preserves manifest/image API asset IDs");
        check(GitHubUpdateSource.requests<=6,"fallback stays inside check request cap");

        GitHubUpdateSource.reset();GitHubUpdateSource.feedError="raw source HTTP 404";
        GitHubUpdateSource.releases=new JSONArray().put(release("blocked","2026-11-08T00:00:00Z",30,false));
        GitHubUpdateSource.indexError="api asset HTTP 403 rate limit";fails("HTTP 403",()->CLIENT.check("175C",false));
        GitHubUpdateSource.reset();GitHubUpdateSource.feedError="raw timeout";GitHubUpdateSource.releasesError="release API HTTP 502";
        fails("HTTP 502",()->CLIENT.check("175C",false));
        GitHubUpdateSource.reset();GitHubUpdateSource.feedError="raw timeout";GitHubUpdateSource.remainingMs=0;
        GitHubUpdateSource.releases=new JSONArray().put(release("deadline","2026-11-08T00:00:00Z",40,false));
        fails("预算",()->CLIENT.check("175C",false));check(GitHubUpdateSource.requestedIndexes.isEmpty(),"exhausted deadline cannot launch another index request");

        GitHubUpdateSource.reset();GitHubUpdateSource.feedError="raw HTTP 404";
        for(int i=0;i<30;i++) { GitHubUpdateSource.releases.put(release("history"+i,"2026-01-01T00:00:00Z",1000+i,false));GitHubUpdateSource.indexes.put(API+(1000+i),new JSONObject().put("schema",99)); }
        fails("最多 3",()->CLIENT.check("175C",false));
        check(GitHubUpdateSource.requestedIndexes.size()==3 && GitHubUpdateSource.requests==5,"thirty historical indices cannot become thirty serial downloads");
        GitHubUpdateSource.reset();GitHubUpdateSource.feedError="two raw sources HTTP 504";GitHubUpdateSource.feedCost=2;
        for(int i=0;i<5;i++) { GitHubUpdateSource.releases.put(release("bounded"+i,"2026-01-01T00:00:00Z",2000+i,false));GitHubUpdateSource.indexes.put(API+(2000+i),index("v7.12.9-nav."+(6-i),false)); }
        check(CLIENT.check("175C",false).getVersion().endsWith("nav.6"),"valid fallback candidate survives exact overall request-budget exhaustion");
        check(GitHubUpdateSource.requestedIndexes.size()==3 && GitHubUpdateSource.requests==6,"cap is evaluated before a spurious budget failure");
        System.out.println("Firmware feed/fallback policy: "+checks+" checks passed against compiled Release Kotlin");
    }
}
