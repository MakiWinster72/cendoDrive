package com.cendodrive.storage;

import com.github.tobato.fastdfs.domain.conn.TrackerConnectionManager;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FastDfsCompatibilityConfig {
    // fastdfs-client 1.27.2 uses javax.annotation.PostConstruct; Spring Boot 3 only processes jakarta.
    // Initialize the tracker locator after fdfs.tracker-list has been bound.
    @Bean
    static BeanPostProcessor fastDfsTrackerInitializer() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof TrackerConnectionManager manager) manager.initTracker();
                return bean;
            }
        };
    }
}
