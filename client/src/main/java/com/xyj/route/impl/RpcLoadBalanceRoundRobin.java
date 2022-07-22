package com.xyj.route.impl;

import com.xyj.vo.RpcConnectionInfo;
import com.xyj.handler.RpcClientHandler;

import com.xyj.route.RpcLoadBalance;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


public class RpcLoadBalanceRoundRobin extends RpcLoadBalance {
    private AtomicInteger roundRobin = new AtomicInteger(0);

    public RpcConnectionInfo doRoute(List<RpcConnectionInfo> addressList) {
        int size = addressList.size();
        // Round robin
        int index = (roundRobin.getAndAdd(1) + size) % size;
        return addressList.get(index);
    }

    @Override
    public RpcConnectionInfo route(String serviceKey, Map<RpcConnectionInfo, RpcClientHandler> connectedServerNodes) throws Exception {
        Map<String, List<RpcConnectionInfo>> serviceMap = getServiceMap(connectedServerNodes);
        List<RpcConnectionInfo> addressList = serviceMap.get(serviceKey);
        if (addressList != null && addressList.size() > 0) {
            return doRoute(addressList);
        } else {
            throw new Exception("Can not find connection for service: " + serviceKey);
        }
    }
}
