package com.dualema.alerts;
import android.net.Uri;
public final class TradingViewLinks {
    private TradingViewLinks() { }
    public static Uri forSymbol(String symbol,String timeframe) {
        String interval;
        switch(timeframe) {case "1h": interval="60";break;case "4h":interval="240";break;case "1w":interval="1W";break;default:interval="15";}
        return Uri.parse("https://www.tradingview.com/chart/").buildUpon()
            .appendQueryParameter("symbol","BINANCE:"+symbol+".P")
            .appendQueryParameter("interval",interval).build();
    }
}
