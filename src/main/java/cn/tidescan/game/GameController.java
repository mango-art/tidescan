package cn.tidescan.game;

import cn.tidescan.market.MarketStore;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/game")
public class GameController {
    private final GameService game;private final MarketStore market;
    public GameController(GameService game,MarketStore market){this.game=game;this.market=market;}
    public record DateRequest(LocalDate day){} public record OrderRequest(String code,String side,double fraction){} public record ResetRequest(String password){}
    @GetMapping public Map<String,Object> state() throws Exception{return game.state();}
    @PostMapping("/start") public Map<String,Object> start(@RequestBody DateRequest r)throws Exception{game.start(r.day());return game.state();}
    @GetMapping("/random-day") public Map<String,String> randomDay() throws Exception {
        try(Connection c=market.open();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT day FROM calendar WHERE day>='2025-01-01' AND day < (SELECT max(day) FROM daily) AND EXISTS(SELECT 1 FROM daily WHERE daily.day=calendar.day) ORDER BY random() LIMIT 1")){if(!r.next())throw new IllegalStateException("行情数据不足，等待同步");return Map.of("day",r.getString(1));}
    }
    @PostMapping("/order") public Map<String,Object> order(@RequestBody OrderRequest r)throws Exception{game.order(r.code(),r.side(),r.fraction());return game.state();}
    @PostMapping("/step") public Map<String,Object> step()throws Exception{game.step();return game.state();}
    @PostMapping("/skip-to-close") public Map<String,Object> skipToClose()throws Exception{game.skipToClose();return game.state();}
    @PostMapping("/next-selection") public Map<String,Object> nextSelection()throws Exception{game.nextSelection();return game.state();}
    @PostMapping("/pass-day") public Map<String,Object> passDay()throws Exception{return game.passDay();}
    @PostMapping("/end") public Map<String,Object> end()throws Exception{return game.end();}
    @PostMapping("/reset") public Map<String,Object> reset(@RequestBody ResetRequest r)throws Exception{game.reset(r.password());return game.state();}
    @GetMapping("/minutes") public List<Map<String,Object>> minutes(@RequestParam String code,@RequestParam(required=false) String day)throws Exception{return game.visible(code,day);}
    @GetMapping("/daily") public List<Map<String,Object>> daily(@RequestParam String code,@RequestParam(required=false) String day,@RequestParam(defaultValue="false") boolean includeClosed)throws Exception{return game.daily(code,day,includeClosed);}
    @GetMapping("/valuation") public Map<String,Object> valuation()throws Exception{return game.valuation();}
}
