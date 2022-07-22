package com.xyj.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

//服务端使用，标识作为rpc的接口类
@Target(value = ElementType.TYPE)
@Retention(value = RetentionPolicy.RUNTIME)
@Component
public @interface RpcService {
    Class<?> value();

    String version() default "";
}
