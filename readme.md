
# SimpleRpc

## 设计与架构

### 整体架构

![Untitled](https://s3-us-west-2.amazonaws.com/secure.notion-static.com/3116416b-568c-43fd-86cf-14e952e68cb4/Untitled.png)

### 协议设计

基于TCP协议，自定义实现了一个简单的传输协议：

长度字段 + 消息内容

长度字段为4个字节

## 功能特性

### 心跳检测

client检查channel空闲状态超过10s，就会发送心跳给server

server对client传来的心跳不做回应

server检查channel空闲状态超过30s，就会当作该client已经断开连接，移除该channel，close掉该连接

### 断线重连

client检测channel inactive时，会尝试x次重连操作，每次重连间隔ts

超过限定次数后，移除该连接

### 服务注册与发现

服务发现

- Client初始化时向ZK请求全量的服务信息缓存到本地
- 添加ZK监视器，当服务变更时会同步更新本地服务缓存

服务注册

- server启动时将暴露的服务接口注册到ZK
- 添加ZK监视器，连接断开时进行重连

### 支持多种调用方式

- 同步调用
- 异步调用

### 序列化

SimpleRPC提供了多种序列化工具供选择

-

### 负载均衡

client在选择服务提供节点时，会进行负载均衡

SimpleRPC提供了多种负载均衡算法

-

## 涉及的技术点

### 动态代理

### Netty

### Spring

### Zookeeper

### JAVA

- 注解
- 反射