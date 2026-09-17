package com.yanglizi.docraptor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DocRaptorApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocRaptorApplication.class, args);
        System.out.println("画个启动ASCII art logo吧");
    }

}
