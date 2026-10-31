package cn.tidescan.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MinuteConcurrencyTest {
    @TempDir Path root;
    @Test void differentStocksCanLoadTogetherAndSameDayIsCached() throws Exception {
        MairuiClient api=mock(MairuiClient.class);ObjectMapper mapper=new ObjectMapper();CountDownLatch started=new CountDownLatch(2);
        when(api.get(anyString(),anyString(),eq(true))).thenAnswer(inv->{started.countDown();if(!started.await(3,TimeUnit.SECONDS))throw new IllegalStateException("requests were serialized");return mapper.readTree("[{\"t\":\"2025-01-02 09:30:00\"}]");});
        MinuteService service=new MinuteService(api,mapper,root.toString());ExecutorService pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(()->service.day("000001.SZ",LocalDate.parse("2025-01-02")));var b=pool.submit(()->service.day("000002.SZ",LocalDate.parse("2025-01-02")));assertEquals(1,a.get(5,TimeUnit.SECONDS).size());assertEquals(1,b.get(5,TimeUnit.SECONDS).size());service.day("000001.SZ",LocalDate.parse("2025-01-02"));verify(api,times(2)).get(anyString(),anyString(),eq(true));}finally{pool.shutdownNow();}
    }
    @Test void simultaneousRequestsForSameStockShareOneDownload() throws Exception {
        MairuiClient api=mock(MairuiClient.class);ObjectMapper mapper=new ObjectMapper();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(api.get(anyString(),anyString(),eq(true))).thenAnswer(inv->{entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");return mapper.readTree("[{\"t\":\"2025-01-02 09:30:00\"}]");});
        MinuteService service=new MinuteService(api,mapper,root.toString());ExecutorService pool=Executors.newFixedThreadPool(2);
        try{var first=pool.submit(()->service.day("000001.SZ",LocalDate.parse("2025-01-02")));assertTrue(entered.await(2,TimeUnit.SECONDS));var second=pool.submit(()->service.day("000001.SZ",LocalDate.parse("2025-01-02")));release.countDown();assertEquals(1,first.get(5,TimeUnit.SECONDS).size());assertEquals(1,second.get(5,TimeUnit.SECONDS).size());verify(api,times(1)).get(anyString(),anyString(),eq(true));}finally{release.countDown();pool.shutdownNow();}
    }
}
