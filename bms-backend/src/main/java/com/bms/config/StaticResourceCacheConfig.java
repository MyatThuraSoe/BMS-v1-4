package com.bms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration
public class StaticResourceCacheConfig implements WebMvcConfigurer {

    /**
     * Vite emits content-hashed filenames (assets/index-&lt;hash&gt;.js), so long-term
     * caching of /assets/** is safe: the browser never re-downloads an unchanged
     * bundle. index.html is deliberately excluded here so it keeps being
     * validated on every load.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
    }
}