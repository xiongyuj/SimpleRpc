package com.xyj.route;


import com.xyj.connect.RpcConnectionInfo;
import com.xyj.connect.RpcServiceInfo;
import com.xyj.handler.RpcClientHandler;
import org.apache.commons.collections4.map.HashedMap;
import com.xyj.util.ServiceUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class RpcLoadBalance {

    /**
     * 得到serviceKey-List<RpcConnectionInfo>的map
     * @param connectedServerNodes
     * @return
     */
    protected Map<String, List<RpcConnectionInfo>> getServiceMap(Map<RpcConnectionInfo, RpcClientHandler> connectedServerNodes) {
        Map<String, List<RpcConnectionInfo>> serviceMap = new HashedMap<>();
        if (connectedServerNodes != null && connectedServerNodes.size() > 0) {
            for (RpcConnectionInfo RpcConnectionInfo : connectedServerNodes.keySet()) {
                for (RpcServiceInfo serviceInfo : RpcConnectionInfo.getServiceInfoList()) {
                    String serviceKey = ServiceUtil.makeServiceKey(serviceInfo.getServiceName(), serviceInfo.getVersion());
                    List<RpcConnectionInfo> RpcConnectionInfoList = serviceMap.get(serviceKey);
                    if (RpcConnectionInfoList == null) {
                        RpcConnectionInfoList = new ArrayList<>();
                    }
                    RpcConnectionInfoList.add(RpcConnectionInfo);
                    serviceMap.putIfAbsent(serviceKey, RpcConnectionInfoList);
                }
            }
        }
        return serviceMap;
    }

    /**
     * 通过负载均衡算法找到此时serviceKey对应的节点
     * @param serviceKey
     * @param connectedServerNodes
     * @return
     * @throws Exception
     */
    public abstract RpcConnectionInfo route(String serviceKey, Map<RpcConnectionInfo, RpcClientHandler> connectedServerNodes) throws Exception;
}
