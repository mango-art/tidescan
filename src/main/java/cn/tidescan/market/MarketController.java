package cn.tidescan.market;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/market")
public class MarketController {
    private final SyncService sync; private final MarketStore store;
    public MarketController(SyncService sync,MarketStore store){this.sync=sync;this.store=store;}
    @GetMapping("/status") public Map<String,Object> status() throws Exception {return Map.of("sync",sync.status(),"storage",store.stats());}
    @PostMapping("/sync") public Map<String,Object> sync(){sync.start();return sync.status();}
}
