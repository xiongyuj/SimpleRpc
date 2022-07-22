package com.xyj;



import org.springframework.context.support.ClassPathXmlApplicationContext;


public class RpcServerStart {
    public static void main(String[] args) {
        new ClassPathXmlApplicationContext("server-spring.xml");
    }
}
