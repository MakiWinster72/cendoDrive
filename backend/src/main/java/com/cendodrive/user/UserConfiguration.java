package com.cendodrive.user;

import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class UserConfiguration {
  @Bean
  Clock accountClock() {
    return Clock.systemUTC();
  }
}
