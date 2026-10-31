package cn.tidescan.strategy;

import java.time.LocalDate;
import java.util.Map;
import cn.tidescan.game.GameService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/screen")
public class ScreenController {
    private final ScreenService service; private final GameService game;
    public ScreenController(ScreenService service,GameService game){this.service=service;this.game=game;}
    public record Request(LocalDate day, Strategy.Parameters parameters) {}
    @GetMapping public Map<String,Object> status() throws Exception {
        Map<String,Object> result=new java.util.LinkedHashMap<>(service.status());var st=game.state();
        String screened=(String)result.get("day"),current=(String)st.get("day");
        if(!Boolean.TRUE.equals(st.get("active")) || current==null || (!screened.isEmpty() && screened.compareTo(current)>0))result.put("matches",java.util.List.of());
        return result;
    }
    @PostMapping public Map<String,Object> start(@RequestBody Request request) throws Exception {
        if(request.day()==null)throw new IllegalArgumentException("请选择日期");
        var state=game.state();
        if(!Boolean.TRUE.equals(state.get("active")) || !request.day().toString().equals(state.get("day")) || !"15:00".equals(state.get("clock")))throw new IllegalStateException("只能在当前游戏日收盘后选股");
        service.start(request.day(),request.parameters()==null?Strategy.Parameters.defaults():request.parameters());return service.status();
    }
}
