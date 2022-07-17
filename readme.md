
# SimpleRpc

# 整体概述

## 设计与架构

### 协议

## 功能特性

### 心跳检测

client检查channel空闲状态超过10s，就会发送心跳给server

server对client传来的心跳不做回应

server检查channel空闲状态超过30s，就会当作该client已经断开连接，移除该channel，close掉该连接

### 断线重连

client检测channel inactive时，会尝试x次重连操作，每次重连间隔ts

超过限定次数后，移除该连接

### 服务注册与发现

### 支持多种调用方式

### 序列化工具选择

### 负载均衡

# 代码实现

### Server

### Client

### Common