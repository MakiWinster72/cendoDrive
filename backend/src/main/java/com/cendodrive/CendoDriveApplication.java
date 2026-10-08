package com.cendodrive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
@org.springframework.context.annotation.Import(com.github.tobato.fastdfs.FdfsClientConfig.class)
@org.springframework.scheduling.annotation.EnableScheduling
public class CendoDriveApplication {
  public static void main(String[] args) {
    SpringApplication.run(CendoDriveApplication.class, args);
  }
}
