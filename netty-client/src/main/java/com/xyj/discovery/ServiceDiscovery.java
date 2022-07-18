package com.xyj.discovery;

import com.xyj.vo.RpcConnectionInfo;
import com.xyj.config.Constant;
import com.xyj.connect.ConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xyj.zookeeper.CuratorClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务发现
 */
@Slf4j
public class ServiceDiscovery {

    private CuratorClient curatorClient;

    public ServiceDiscovery(String registryAddress) {
        this.curatorClient = new CuratorClient(registryAddress);
        //初始化时进行服务发现
        discoveryService();
    }


    /**
     * 服务发现主方法
     */
    private void discoveryService() {
        try {
            log.info("Get initial service info");
            getServiceAndUpdateServer();
            // 添加监听器
            curatorClient.watchPathChildrenNode(Constant.ZK_REGISTRY_PATH, new PathChildrenCacheListener() {
                @Override
                public void childEvent(CuratorFramework curatorFramework, PathChildrenCacheEvent pathChildrenCacheEvent) throws Exception {
                    PathChildrenCacheEvent.Type type = pathChildrenCacheEvent.getType();
                    ChildData childData = pathChildrenCacheEvent.getData();
                    switch (type) {
                        case CONNECTION_RECONNECTED:
                            log.info("Reconnected to zk, try to get latest service list");
                            getServiceAndUpdateServer();
                            break;
                        case CHILD_ADDED:
                            updateZKServerCache(childData, PathChildrenCacheEvent.Type.CHILD_ADDED);
                            break;
                        case CHILD_UPDATED:
                            updateZKServerCache(childData, PathChildrenCacheEvent.Type.CHILD_UPDATED);
                            break;
                        case CHILD_REMOVED:
                            updateZKServerCache(childData, PathChildrenCacheEvent.Type.CHILD_REMOVED);
                            break;
                    }
                }
            });
        } catch (Exception ex) {
            log.error("Watch node exception: " + ex.getMessage());
        }
    }

    /**
     * 连接建立后向注册中心获取全部注册的服务并在本地进行缓存
     * 初次连接或重连
     */
    private void getServiceAndUpdateServer() {
        try {
            List<String> nodeList = curatorClient.getChildren(Constant.ZK_REGISTRY_PATH);
            List<RpcConnectionInfo> dataList = new ArrayList<>();
            for (String node : nodeList) {
                log.debug("Service node: " + node);
                byte[] bytes = curatorClient.getData(Constant.ZK_REGISTRY_PATH + "/" + node);
                String json = new String(bytes);
                RpcConnectionInfo rpcConnectionInfo = RpcConnectionInfo.fromJson(json);
                dataList.add(rpcConnectionInfo);
            }
            log.debug("Service node data: {}", dataList);
            //Update the service info based on the latest data
            updateConnectedServerByList(dataList);
        } catch (Exception e) {
            log.error("Get node exception: " + e.getMessage());
        }
    }

    /**
     * zk本地服务信息更新
     * @param childData
     * @param type
     */
    private void updateZKServerCache(ChildData childData, PathChildrenCacheEvent.Type type) {
        String path = childData.getPath();
        String data = new String(childData.getData(), StandardCharsets.UTF_8);
        log.info("Child data updated, path:{},type:{},data:{},", path, type, data);
        RpcConnectionInfo rpcConnectionInfo =  RpcConnectionInfo.fromJson(data);
        updateServerCache(rpcConnectionInfo, type);
    }

    /**
     * 批量更新节点连接信息
     * @param dataList
     */
    private void updateConnectedServerByList(List<RpcConnectionInfo> dataList) {
        ConnectionManager.getInstance().updateConnectedServer(dataList);
    }

    /**
     * 更新本地单个节点连接信息
     * @param RpcConnectionInfo
     * @param type
     */
    private void updateServerCache(RpcConnectionInfo RpcConnectionInfo, PathChildrenCacheEvent.Type type) {
        ConnectionManager.getInstance().updateConnectedServer(RpcConnectionInfo, type);
    }

    public void stop() {
        this.curatorClient.close();
    }
}
