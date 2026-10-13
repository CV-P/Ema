package com.dualema.alerts;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.preference.PreferenceManager;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

public final class MainActivity extends Activity {
    private static final int BG=0xff0b1220, SURFACE=0xff152239, BORDER=0xff2a3a53;
    private static final int TEXT=0xfff0f5fc, MUTED=0xffa9bcd4, TEAL=0xff45d7bb, RED=0xffff8197;
    private static final String[] TF_LABEL={"All timeframes","15m","1h","4h","1w"};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView status, topInfo, empty;
    private Button toggle, ema915, ema833;
    private Spinner timeframe;
    private LinearLayout eventsHolder;
    private boolean active=false;
    private String strategy="EMA 9/15", selectedTF="ALL", previousFingerprint="";
    private final Runnable refreshTask=new Runnable(){@Override public void run(){refresh();if(active)handler.postDelayed(this,5000);}};

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs=PreferenceManager.getDefaultSharedPreferences(this);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        strategy=prefs.getString("active_pair","EMA 9/15");
        selectedTF=prefs.getString("alert_tf","ALL");
        buildUI();
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1001);
    }
    private void buildUI() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16),dp(19),dp(16),dp(24));scroll.addView(page);setContentView(scroll);

        TextView title=label("DUAL EMA · CROSSOVER ALERTS",22,TEXT,true);page.addView(title);
        TextView subtitle=label("V2.4 Mobile · Top 100 USDT perpetuals",12,MUTED,false);subtitle.setPadding(0,dp(6),0,dp(16));page.addView(subtitle);
        LinearLayout health=panel(); page.addView(health,full());
        status=label("Scanner stopped",13,MUTED,false);health.addView(status);
        topInfo=label("Only confirmed EMA crosses. No orders or paper-trading tables.",12,MUTED,false);
        topInfo.setPadding(0,dp(8),0,dp(10));health.addView(topInfo);
        toggle=new Button(this);toggle.setAllCaps(false);toggle.setText("Start Scanner & Alerts");
        toggle.setTextColor(Color.WHITE); toggle.setBackground(round(0xff247e77,12));
        toggle.setOnClickListener(v->toggle()); health.addView(toggle,full());

        TextView pairTitle=label("CROSSOVER FEEDS",12,MUTED,true);
        pairTitle.setPadding(0,dp(22),0,dp(9)); page.addView(pairTitle);
        LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL); page.addView(tabs);
        ema915=tabButton("EMA 9/15",tabs);ema833=tabButton("EMA 8/33",tabs);
        ema915.setOnClickListener(v->choosePair("EMA 9/15"));ema833.setOnClickListener(v->choosePair("EMA 8/33"));

        TextView filterTitle=label("TIMEFRAME",12,MUTED,true);
        filterTitle.setPadding(0,dp(16),0,dp(6));page.addView(filterTitle);
        timeframe=new Spinner(this);timeframe.setBackground(round(SURFACE,10));
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,TF_LABEL);
        timeframe.setAdapter(adapter);page.addView(timeframe,full());
        int chosen=0;for(int i=1;i<TF_LABEL.length;i++)if(TF_LABEL[i].equals(selectedTF))chosen=i;
        timeframe.setSelection(chosen);
        timeframe.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                selectedTF=pos==0?"ALL":TF_LABEL[pos];prefs.edit().putString("alert_tf",selectedTF).apply();previousFingerprint="";refresh();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        empty=label("No recent crossovers in this feed.",14,MUTED,false);
        empty.setPadding(0,dp(20),0,dp(12));page.addView(empty);
        eventsHolder=new LinearLayout(this);eventsHolder.setOrientation(LinearLayout.VERTICAL);page.addView(eventsHolder,full());
        TextView foot=label("Tap an alert to open its live TradingView symbol and timeframe. Binance connectivity is required. Android battery restrictions may interrupt background scanning.",12,MUTED,false);
        foot.setPadding(0,dp(24),0,0);page.addView(foot);
        updateTabs();
    }
    private Button tabButton(String text,LinearLayout container) {
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.setMargins(0,0,dp(9),0);container.addView(b,lp);return b;
    }
    private LinearLayout panel() {
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14),dp(14),dp(14),dp(14));l.setBackground(round(SURFACE,15));return l;
    }
    private GradientDrawable round(int color,int radius) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));d.setStroke(dp(1),BORDER);return d;
    }
    private TextView label(String s,int sp,int color,boolean bold) {
        TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout.LayoutParams full() {return new LinearLayout.LayoutParams(-1,-2);}
    private int dp(int size){return Math.round(getResources().getDisplayMetrics().density*size);}
    private void choosePair(String pair) {
        strategy=pair;prefs.edit().putString("active_pair",pair).apply();previousFingerprint="";updateTabs();refresh();
    }
    private void updateTabs() {
        ema915.setTextColor(TEXT);ema833.setTextColor(TEXT);
        ema915.setBackground(round("EMA 9/15".equals(strategy)?0xff246a69:SURFACE,12));
        ema833.setBackground(round("EMA 8/33".equals(strategy)?0xff246a69:SURFACE,12));
    }
    private void toggle() {
        boolean started=prefs.getBoolean(ScannerService.PREF_RUNNING,false);
        Intent i=new Intent(this,ScannerService.class);
        try {
            if(started){stopService(i);prefs.edit().putBoolean(ScannerService.PREF_RUNNING,false).apply();}
            else {if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
        } catch(Exception ex) {Toast.makeText(this,"Unable to start background scanner: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
        handler.postDelayed(this::refresh,400);
    }
    @Override protected void onResume(){super.onResume();active=true;handler.removeCallbacks(refreshTask);handler.post(refreshTask);}
    @Override protected void onPause(){active=false;handler.removeCallbacks(refreshTask);super.onPause();}
    private void refresh() {
        if(prefs==null || status==null || eventsHolder==null)return;
        boolean on=prefs.getBoolean(ScannerService.PREF_RUNNING,false);
        status.setText((on?"● Active · ":"○ Stopped · ")+prefs.getString(ScannerService.PREF_STATUS,"Start scanner to receive alerts"));
        status.setTextColor(on?TEAL:MUTED);
        toggle.setText(on?"Stop Scanner":"Start Scanner & Alerts");
        String raw=prefs.getString(ScannerService.PREF_EVENTS,"[]");
        String key=strategy+"|"+selectedTF+"|"+raw.hashCode();
        if(key.equals(previousFingerprint))return;
        previousFingerprint=key;
        eventsHolder.removeAllViews();
        List<JSONObject> items=new ArrayList<>();
        try {
            JSONArray list=new JSONArray(raw);
            for(int i=0;i<list.length();i++) {
                JSONObject row=list.optJSONObject(i);
                if(row!=null && strategy.equals(row.optString("strategy")) && ("ALL".equals(selectedTF)||selectedTF.equals(row.optString("tf"))))items.add(row);
            }
        } catch(Exception ignored) {}
        items.sort((a,b)->Long.compare(b.optLong("bar_t"),a.optLong("bar_t")));
        empty.setVisibility(items.isEmpty()?View.VISIBLE:View.GONE);
        int limit=Math.min(75,items.size());
        for(int i=0;i<limit;i++)addAlert(items.get(i));
    }
    private void addAlert(JSONObject item) {
        String symbol=item.optString("symbol"),tf=item.optString("tf"),side=item.optString("side");
        boolean bullish=side.startsWith("BULL");
        LinearLayout card=panel();
        LinearLayout.LayoutParams cLp=full();cLp.setMargins(0,0,0,dp(9));eventsHolder.addView(card,cLp);
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);card.addView(top,full());
        TextView name=label(symbol+" · "+tf,17,TEXT,true);
        top.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        TextView type=label(bullish?"▲ BULL":"▼ BEAR",13,bullish?TEAL:RED,true);top.addView(type);
        TextView meta=label(item.optString("time")+"  ·  Reference "+String.format(Locale.US,"%.8g",item.optDouble("reference_price")),12,MUTED,false);
        meta.setPadding(0,dp(8),0,dp(3));card.addView(meta);
        TextView link=label("Open live TradingView ↗",12,0xff9fc6ff,true);link.setPadding(0,dp(7),0,0);card.addView(link);
        View.OnClickListener go=v->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,TradingViewLinks.forSymbol(symbol,tf)));}
            catch(Exception e){Toast.makeText(this,"Cannot open TradingView",Toast.LENGTH_SHORT).show();}
        };
        card.setClickable(true);card.setOnClickListener(go);
    }
}
