package cn.tidescan;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TideScanApplication {
    public static void main(String[] args) { SpringApplication.run(TideScanApplication.class, args); }
    @Bean
    TomcatServletWebServerFactory servletContainer() {
        var factory=new TomcatServletWebServerFactory();
        factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");
        return factory;
    }
}
