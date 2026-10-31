package cn.tidescan;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
    public ResponseEntity<Map<String,String>> badRequest(RuntimeException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()==null?"请求失败":e.getMessage()));}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,String>> failed(Exception e){return ResponseEntity.internalServerError().body(Map.of("message","操作失败，请检查行情数据与服务日志"));}
}
