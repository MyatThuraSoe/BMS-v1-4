package com.bms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode;

@SpringBootApplication
@EnableSpringDataWebSupport(pageSerializationMode = PageSerializationMode.VIA_DTO)
public class BmsApplication {

    @Value("${app.open-browser:false}")
    private boolean openBrowser;

    public static void main(String[] args) {
        SpringApplication.run(BmsApplication.class, args);
    }

    @Bean
    public ApplicationListener<WebServerInitializedEvent> openBrowserOnReady() {
        return event -> {
            if (!openBrowser) {
                return;
            }

            try {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start",
                            "http://127.0.0.1:" + event.getWebServer().getPort()});
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
    }
}