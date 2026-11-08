package com.example.motonav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only the shared network boundary is substituted; candidate parsing/selection is compiled production Kotlin. */
public final class GitHubUpdateSource {
    public static final GitHubUpdateSource INSTANCE = new GitHubUpdateSource();
    public static JSONObject feed;
    public static JSONArray releases;
    public static String feedError, releasesError, indexError;
    public static int remainingMs, requests, releaseRequests, feedCost;
    public static final Map<String,JSONObject> indexes = new HashMap<>();
    public static final List<String> requestedIndexes = new ArrayList<>();
    public static void reset() {
        feed=new JSONObject().put("schema",1).put("firmwares",new JSONArray());releases=new JSONArray();
        feedError=releasesError=indexError="";remainingMs=15000;requests=releaseRequests=0;feedCost=1;indexes.clear();requestedIndexes.clear();
    }
    public Session session() { return new Session(); }
    public static final class Session {
        public JSONObject feed() { requests+=feedCost;if(!feedError.isEmpty())throw new IllegalStateException(feedError);return feed; }
        public JSONArray releases() { requests++;releaseRequests++;if(!releasesError.isEmpty())throw new IllegalStateException(releasesError);return releases; }
        public JSONObject jsonObject(String url,int maxBytes) {
            if(++requests>6)throw new AssertionError("unbounded check request count");
            requestedIndexes.add(url);if(!indexError.isEmpty())throw new IllegalStateException(indexError);
            JSONObject result=indexes.get(url);if(result==null)throw new AssertionError("unexpected index request: "+url);return result;
        }
        public int remainingMs() { return remainingMs; }
        public int remainingRequests() { return 6-requests; }
    }
}
