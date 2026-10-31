package cn.tidescan.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.HttpURLConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Credentials are confined to this server-side client and never included in errors. */
@Component
public class MairuiClient {
    private final String daily, minute;
    private final ObjectMapper mapper;
    private static final class Limiter { long next; }
    private final Limiter dailyLimit=new Limiter(),minuteLimit=new Limiter();
    private final long minuteIntervalNanos;

    public MairuiClient(@Value("${mairui.daily-licence:}") String daily,
                        @Value("${mairui.minute-licence:}") String minute, ObjectMapper mapper,
                        @Value("${mairui.minute-interval-ms:500}") long minuteIntervalMs) {
        this.daily = daily; this.minute = minute; this.mapper = mapper;this.minuteIntervalNanos=java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(250,minuteIntervalMs));
    }

    public JsonNode get(String prefix, String query, boolean isMinute) {
        String key = isMinute ? minute : daily;
        if (key.isBlank()) throw new IllegalStateException("麦瑞凭证未配置");
        try {
            Limiter limiter=isMinute&&!minute.equals(daily)?minuteLimit:dailyLimit;
            long interval=limiter==minuteLimit?minuteIntervalNanos:1_000_000_000L;
            for(int attempt=0;attempt<3;attempt++){
                synchronized(limiter){long wait=limiter.next-System.nanoTime();if(wait>0)java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);limiter.next=System.nanoTime()+interval;}
                HttpURLConnection connection=(HttpURLConnection)URI.create("https://api.mairuiapi.com/" + prefix + "/" + key + query).toURL().openConnection();
                connection.setConnectTimeout(15000);connection.setReadTimeout(35000);
                try {
                    int status=connection.getResponseCode();
                    if(status==429&&attempt<2){long pause=2000L*(1L<<attempt);String retry=connection.getHeaderField("Retry-After");if(retry!=null)try{pause=Math.min(30000,Math.max(pause,Long.parseLong(retry.trim())*1000));}catch(NumberFormatException ignored){}synchronized(limiter){limiter.next=Math.max(limiter.next,System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(pause));}continue;}
                    if (status != 200) throw new IllegalStateException("麦瑞接口返回 HTTP " + status);
                    JsonNode data=mapper.readTree(connection.getInputStream());
                    if (!data.isArray()) throw new IllegalStateException("麦瑞接口返回异常数据，请检查权限或配额");
                    return data;
                } finally { connection.disconnect(); }
            }
            throw new IllegalStateException("麦瑞接口限流，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("请求被中断");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("麦瑞接口连接失败，请检查网络");
        }
    }
}
