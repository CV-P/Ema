package com.dualema.alerts;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Standalone Binance USD-M scanner. No orders, paper trade engine, or desktop dependency. */
public final class ScannerService extends Service {
    public static final String PREF_RUNNING = "scanner_running";
    public static final String PREF_STATUS = "scanner_status";
    public static final String PREF_EVENTS = "recent_crosses";
    private static final String BASE = "https://fapi.binance.com/fapi/v1/";
    private static final String CHANNEL_STATUS = "scanner_status_v1";
    private static final String CHANNEL_CROSS = "crossovers_v1";
    private static final String[] TFS = {"15m", "1h", "4h", "1w"};
    private static final int TOP_N = 100;
    private static final long WEEK_OFFSET_MS = 345600000L; // Monday 1970-01-05 00:00 UTC
    private static final long WEEK_MS = 604800000L;
    private final Object historyLock = new Object();
    private final Map<String, Long> scannedBucket = new HashMap<>();
    private final LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
    private volatile boolean running;
    private Thread worker;
    private android.content.SharedPreferences prefs;
    private NotificationManager notificationManager;
    private int noticeId = 3000;

    @Override public void onCreate() {
        super.onCreate();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        notificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(new NotificationChannel(CHANNEL_STATUS,"Scanner running",NotificationManager.IMPORTANCE_LOW));
        notificationManager.createNotificationChannel(new NotificationChannel(CHANNEL_CROSS,"New EMA crossovers",NotificationManager.IMPORTANCE_HIGH));
        JSONArray saved = parseArray(prefs.getString("seen_keys", "[]"));
        for (int i=0;i<saved.length();i++) { String key = saved.optString(i,""); if (!key.isEmpty()) seenKeys.add(key); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this,4,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification note = new Notification.Builder(this,CHANNEL_STATUS)
            .setContentTitle("Dual EMA · Top 100 scanning")
            .setContentText("15m · 1H · 4H · 1W — confirmed crossovers only")
            .setContentIntent(pending).setOngoing(true)
            .setSmallIcon(android.R.drawable.stat_notify_sync).build();
        if (Build.VERSION.SDK_INT>=29)
            startForeground(1,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1,note);
        if (worker == null || !worker.isAlive()) {
            running=true;
            prefs.edit().putBoolean(PREF_RUNNING,true).apply();
            worker=new Thread(this::runScanner,"dual-ema-top100");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private void runScanner() {
        while (running) {
            try {
                setStatus("Loading Top 100 Binance futures markets…");
                List<String> symbols=getUniverse();
                int attempted=0, successes=0, found=0;
                boolean bootstrap=!prefs.getBoolean("initial_scan_done",false);
                for (String tf:TFS) {
                    if(!running) break;
                    long key=barBucket(tf,System.currentTimeMillis());
                    if (scannedBucket.containsKey(tf) && scannedBucket.get(tf)==key) continue;
                    setStatus("Checking "+tf+" · "+symbols.size()+" markets…");
                    ScanCount count=scanTimeframe(tf,symbols,bootstrap);
                    attempted+=count.attempted;
                    successes+=count.ok;
                    found+=count.signals;
                    // Do not repeatedly download the entire universe between candle closes.
                    if(count.ok>0) scannedBucket.put(tf,key);
                }
                if(successes>0 && bootstrap) prefs.edit().putBoolean("initial_scan_done",true).apply();
                String s="Top 100 · "+(attempted>0?successes+"/"+attempted+" symbol/TF checks · "+found+" crosses":"waiting for next close");
                if(attempted>0 && successes<attempted) s+=" · Some markets unavailable";
                setStatus(s);
            } catch (Exception e) {
                setStatus("Binance unavailable: "+shortMessage(e)+" · retrying");
            }
            for(int i=0;i<90 && running;i++) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); running=false; break; }
            }
        }
    }
    private ScanCount scanTimeframe(String tf,List<String> symbols,boolean bootstrap) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(5);
        ScanCount count = new ScanCount();
        List<Future<Integer>> jobs=new ArrayList<>();
        try {
            for(String sym:symbols) {
                if(!running) break;
                jobs.add(pool.submit(()->checkMarket(sym,tf,bootstrap)));
            }
            for(Future<Integer> f:jobs) {
                count.attempted++;
                try {
                    int n=f.get(35,TimeUnit.SECONDS);
                    count.ok++;
                    count.signals+=n;
                } catch(Exception ignored) { f.cancel(true); }
            }
        } finally { pool.shutdownNow(); }
        return count;
    }
    private static class ScanCount {int attempted,ok,signals;}

    private int checkMarket(String sym,String tf,boolean bootstrap) throws Exception {
        if(!running) return 0;
        String query="klines?symbol="+URLEncoder.encode(sym,"UTF-8")+"&interval="+tf+"&limit=500";
        JSONArray rows=new JSONArray(request(BASE+query));
        ArrayList<Double> prices=new ArrayList<>();
        long lastT=0;
        double lastClose=0;
        long now=System.currentTimeMillis();
        for(int i=0;i<rows.length();i++) {
            JSONArray b=rows.getJSONArray(i);
            if(b.getLong(6)>=now) continue; // ignore the currently forming candle
            lastT=b.getLong(0);
            lastClose=Double.parseDouble(b.getString(4));
            prices.add(lastClose);
        }
        if(prices.size()<65) return 0; // newly listed contracts may lack weekly data
        double[] closes=new double[prices.size()];
        for(int i=0;i<closes.length;i++)closes[i]=prices.get(i);
        int added=0;
        int d915=CrossoverMath.direction(closes,9,15);
        int d833=CrossoverMath.direction(closes,8,33);
        if(d915!=0 && record(sym,tf,"EMA 9/15",d915,lastT,lastClose,bootstrap)) added++;
        if(d833!=0 && record(sym,tf,"EMA 8/33",d833,lastT,lastClose,bootstrap)) added++;
        return added;
    }
    private boolean record(String sym,String tf,String ema,int direction,long barT,double close,boolean bootstrap) {
        long now=System.currentTimeMillis();
        long age=now-(barT+intervalMillis(tf)-1);
        long maxAge="15m".equals(tf)?1800000L:"1h".equals(tf)?7200000L:10800000L;
        if(age<0 || age>maxAge) return false;
        String key=sym+"/"+tf+"/"+ema+"/"+barT;
        synchronized(historyLock) {
            if(seenKeys.contains(key)) return false;
            seenKeys.add(key);
            while(seenKeys.size()>3500) seenKeys.remove(seenKeys.iterator().next());
            try {
                JSONObject item=new JSONObject();
                item.put("symbol",sym); item.put("tf",tf); item.put("strategy",ema);
                item.put("side",direction>0?"BULL CROSS":"BEAR CROSS");
                item.put("bar_t",barT); item.put("reference_price",close);
                item.put("time",formatUTC(barT)); item.put("observed",formatUTC(now));
                JSONArray prev=parseArray(prefs.getString(PREF_EVENTS,"[]"));
                JSONArray out=new JSONArray();
                out.put(item);
                for(int i=0;i<prev.length() && i<199;i++)out.put(prev.optJSONObject(i));
                JSONArray keys=new JSONArray();for(String v:seenKeys) keys.put(v);
                prefs.edit().putString(PREF_EVENTS,out.toString()).putString("seen_keys",keys.toString()).apply();
            } catch(Exception e) {setStatus("History write error: "+shortMessage(e));}
        }
        if(!bootstrap) sendCrossoverNotification(sym,tf,ema,direction,barT);
        return true;
    }
    private void sendCrossoverNotification(String sym,String tf,String ema,int dir,long barT) {
        String title=sym+" · "+tf+" · "+ema;
        String body=(dir>0?"BULL CROSS":"BEAR CROSS")+" · confirmed "+formatUTC(barT);
        Uri url=TradingViewLinks.forSymbol(sym,tf);
        Intent chart=new Intent(Intent.ACTION_VIEW,url);
        chart.addCategory(Intent.CATEGORY_BROWSABLE);
        PendingIntent p=PendingIntent.getActivity(this,++noticeId,chart,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL_CROSS)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title).setContentText(body)
            .setContentIntent(p).setAutoCancel(true)
            .setGroup(ema).build();
        try{notificationManager.notify(noticeId,n);}catch(SecurityException ignored) { }
    }
    private List<String> getUniverse() throws Exception {
        long last=prefs.getLong("universe_cached_at",0);
        JSONArray old=parseArray(prefs.getString("universe_cached","[]"));
        if(old.length()>=TOP_N && System.currentTimeMillis()-last<6*3600000L) return toStrings(old);
        try {
            JSONObject info=new JSONObject(request(BASE+"exchangeInfo"));
            JSONArray arr=info.getJSONArray("symbols");
            HashSet<String> eligible=new HashSet<>();
            for(int i=0;i<arr.length();i++) {
                JSONObject a=arr.getJSONObject(i);
                String sym=a.optString("symbol","");
                if(sym.endsWith("USDT") && "USDT".equals(a.optString("quoteAsset"))
                    && "PERPETUAL".equals(a.optString("contractType"))
                    && "TRADING".equals(a.optString("status")))eligible.add(sym);
            }
            JSONArray tickers=new JSONArray(request(BASE+"ticker/24hr"));
            ArrayList<VolumeEntry> ranked=new ArrayList<>();
            for(int i=0;i<tickers.length();i++) {
                JSONObject t=tickers.getJSONObject(i);
                String sym=t.optString("symbol","");
                if(eligible.contains(sym)) {
                    double v=t.optDouble("quoteVolume",0);
                    if(v>0)ranked.add(new VolumeEntry(sym,v));
                }
            }
            ranked.sort((a,b)-> { int cmp=Double.compare(b.volume,a.volume);return cmp!=0?cmp:b.symbol.compareTo(a.symbol); });
            if(ranked.size()<TOP_N)throw new IOException("Only "+ranked.size()+" eligible contracts; refusing incomplete Top 100");
            JSONArray newCache=new JSONArray();List<String> result=new ArrayList<>();
            for(int i=0;i<TOP_N;i++) {newCache.put(ranked.get(i).symbol);result.add(ranked.get(i).symbol);}
            prefs.edit().putString("universe_cached",newCache.toString())
                .putLong("universe_cached_at",System.currentTimeMillis()).apply();
            return result;
        } catch(Exception e) {
            if(old.length()>=TOP_N) { setStatus("Top 100 refresh unavailable; using cached active universe");return toStrings(old); }
            throw e;
        }
    }
    private static final class VolumeEntry { final String symbol;final double volume;VolumeEntry(String s,double v){symbol=s;volume=v;} }
    private static List<String> toStrings(JSONArray a) {List<String> out=new ArrayList<>();for(int i=0;i<a.length() && i<TOP_N;i++)out.add(a.optString(i));return out;}
    private static String request(String url) throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
        try {
            conn.setConnectTimeout(12000);conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent","Dual-EMA-V2.4-Mobile/1.0");
            int status=conn.getResponseCode();
            if(status!=200)throw new IOException("Binance HTTP "+status);
            try(InputStream in=conn.getInputStream();Reader rd=new InputStreamReader(in,"UTF-8");BufferedReader br=new BufferedReader(rd)) {
                StringBuilder buf=new StringBuilder(); String line;
                while((line=br.readLine())!=null) {buf.append(line);if(buf.length()>10000000)throw new IOException("Response too large");}
                return buf.toString();
            }
        } finally {conn.disconnect();}
    }
    private static JSONArray parseArray(String s) {try{return new JSONArray(s);}catch(Exception e){return new JSONArray();}}
    private static long intervalMillis(String tf) {switch(tf){case "15m":return 900000L;case "1h":return 3600000L;case "4h":return 14400000L;default:return WEEK_MS;}}
    private static long barBucket(String tf,long now) {
        long adjusted=now-8000L; long period=intervalMillis(tf);
        return "1w".equals(tf)?Math.floorDiv(adjusted-WEEK_OFFSET_MS,period):Math.floorDiv(adjusted,period);
    }
    private static String formatUTC(long ms) {
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'",Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date(ms));
    }
    private static String shortMessage(Exception e) {String s=e.getMessage();if(s==null)return e.getClass().getSimpleName();return s.substring(0,Math.min(100,s.length()));}
    private void setStatus(String s) {if(prefs!=null)prefs.edit().putString(PREF_STATUS,s).apply();}
    @Override public void onDestroy() {
        running=false; if(worker!=null)worker.interrupt();
        if(prefs!=null)prefs.edit().putBoolean(PREF_RUNNING,false).putString(PREF_STATUS,"Scanner stopped").apply();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) {return null;}
}
