package com.xyj.connect;


import com.xyj.handler.RpcClientHandler;
import com.xyj.handler.RpcClientInitializer;
import com.xyj.vo.RpcConnectionInfo;
import com.xyj.vo.RpcServiceInfo;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.xyj.route.RpcLoadBalance;
import com.xyj.route.impl.RpcLoadBalanceRoundRobin;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 管理client的所有RPC连接
 */
@Slf4j
public class ConnectionManager {

    private EventLoopGroup eventLoopGroup = new NioEventLoopGroup(4);

    private static ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(4, 8,
            600L, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1000));

    private Map<RpcConnectionInfo, RpcClientHandler> connectedServerNodes = new ConcurrentHashMap<>();

    private CopyOnWriteArraySet<RpcConnectionInfo> RpcConnectionInfoSet = new CopyOnWriteArraySet<>();

    private ReentrantLock lock = new ReentrantLock();

    private Condition connected = lock.newCondition();

    private long waitTimeout = 5000;

    private RpcLoadBalance loadBalance = new RpcLoadBalanceRoundRobin();

    private volatile boolean isRunning = true;

    private ConnectionManager() {}

    private static class SingletonHolder {
        private static final ConnectionManager instance = new ConnectionManager();
    }

    public static ConnectionManager getInstance() {
        return SingletonHolder.instance;
    }



    /**
     * 更新缓存节点
     * @param serviceList
     */
    public void updateConnectedServer(List<RpcConnectionInfo> serviceList) {
        // Now using 2 collections to manage the service info and TCP connections because making the connection is async
        // Once service info is updated on ZK, will trigger this function
        // Actually client should only care about the service it is using
        if (serviceList != null && serviceList.size() > 0) {
            // Update local server nodes cache
            HashSet<RpcConnectionInfo> serviceSet = new HashSet<>(serviceList.size());
            for (int i = 0; i < serviceList.size(); ++i) {
                RpcConnectionInfo RpcConnectionInfo = serviceList.get(i);
                serviceSet.add(RpcConnectionInfo);
            }

            // Add new server info
            for (final RpcConnectionInfo RpcConnectionInfo : serviceSet) {
                if (!RpcConnectionInfoSet.contains(RpcConnectionInfo)) {
                    connectServerNode(RpcConnectionInfo);
                }
            }

            // Close and remove invalid server nodes
            for (RpcConnectionInfo RpcConnectionInfo : RpcConnectionInfoSet) {
                if (!serviceSet.contains(RpcConnectionInfo)) {
                    log.info("Remove invalid service: " + RpcConnectionInfo.toJson());
                    removeAndCloseHandler(RpcConnectionInfo);
                }
            }
        } else {
            // No available service
            log.error("No available service!");
            for (RpcConnectionInfo RpcConnectionInfo : RpcConnectionInfoSet) {
                removeAndCloseHandler(RpcConnectionInfo);
            }
        }
    }

    /**
     * 根据给定的节点来更新缓存节点
     * @param RpcConnectionInfo
     * @param type
     */
    public void updateConnectedServer(RpcConnectionInfo RpcConnectionInfo, PathChildrenCacheEvent.Type type) {
        if (RpcConnectionInfo == null) {
            return;
        }
        if (type == PathChildrenCacheEvent.Type.CHILD_ADDED && !RpcConnectionInfoSet.contains(RpcConnectionInfo)) {
            connectServerNode(RpcConnectionInfo);
        } else if (type == PathChildrenCacheEvent.Type.CHILD_UPDATED) {
            //TODO We may don't need to reconnect remote server if the server'IP and server'port are not changed
            removeAndCloseHandler(RpcConnectionInfo);
            connectServerNode(RpcConnectionInfo);
        } else if (type == PathChildrenCacheEvent.Type.CHILD_REMOVED) {
            removeAndCloseHandler(RpcConnectionInfo);
        } else {
            throw new IllegalArgumentException("Unknow type:" + type);
        }
    }

    /**
     * 建立连接
     * @param RpcConnectionInfo
     */

    private void connectServerNode(RpcConnectionInfo RpcConnectionInfo) {
        if (RpcConnectionInfo.getServiceInfoList() == null || RpcConnectionInfo.getServiceInfoList().isEmpty()) {
            log.info("No service on node, host: {}, port: {}", RpcConnectionInfo.getHost(), RpcConnectionInfo.getPort());
            return;
        }
        RpcConnectionInfoSet.add(RpcConnectionInfo);
        log.info("New service node, host: {}, port: {}", RpcConnectionInfo.getHost(), RpcConnectionInfo.getPort());
        for (RpcServiceInfo serviceProtocol : RpcConnectionInfo.getServiceInfoList()) {
            log.info("New service info, name: {}, version: {}", serviceProtocol.getServiceName(), serviceProtocol.getVersion());
        }
        final InetSocketAddress remotePeer = new InetSocketAddress(RpcConnectionInfo.getHost(), RpcConnectionInfo.getPort());
        //将连接任务提交给线程池
        threadPoolExecutor.submit(new Runnable() {
            @Override
            public void run() {
                Bootstrap b = new Bootstrap();
                b.group(eventLoopGroup)
                        .channel(NioSocketChannel.class)
                        .handler(new RpcClientInitializer());

                ChannelFuture channelFuture = b.connect(remotePeer);
                channelFuture.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture channelFuture) throws Exception {
                        if (channelFuture.isSuccess()) {
                            log.info("Successfully connect to remote server, remote peer = " + remotePeer);
                            RpcClientHandler handler = channelFuture.channel().pipeline().get(RpcClientHandler.class);
                            connectedServerNodes.put(RpcConnectionInfo, handler);
                            handler.setRpcConnectionInfo(RpcConnectionInfo);
                            signalAvailableHandler();
                        } else {
                            log.error("Can not connect to remote server, remote peer = " + remotePeer);
                        }
                    }
                });
            }
        });
    }

    /**
     * 通知等待的线程返回
     */

    private void signalAvailableHandler() {
        lock.lock();
        try {
            connected.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 当没有可用handler时进行等待
     * @return
     * @throws InterruptedException
     */

    private boolean waitingForHandler() throws InterruptedException {
        lock.lock();
        try {
            log.warn("Waiting for available service");
            return connected.await(this.waitTimeout, TimeUnit.MILLISECONDS);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 根据serviceKey找到对应的RpcClientHandler，如果不存在则抛出异常（此时channel应该已经建立，因此handler理应存在）
     * @param serviceKey
     * @return RpcClientHandler
     * @throws Exception
     */
    public RpcClientHandler chooseHandler(String serviceKey) throws Exception {
        int size = connectedServerNodes.values().size();
        while (isRunning && size <= 0) {
            try {
                waitingForHandler();
                size = connectedServerNodes.values().size();
            } catch (InterruptedException e) {
                log.error("Waiting for available service is interrupted!", e);
            }
        }
        RpcConnectionInfo RpcConnectionInfo = loadBalance.route(serviceKey, connectedServerNodes);
        RpcClientHandler handler = connectedServerNodes.get(RpcConnectionInfo);
        if (handler != null) {
            return handler;
        } else {
            throw new Exception("Can not get available connection");
        }
    }

    /**
     * 根据RpcConnectionInfo移除并关闭该节点和对应的RpcClientHandler
     * @param RpcConnectionInfo
     */

    private void removeAndCloseHandler(RpcConnectionInfo RpcConnectionInfo) {
        RpcClientHandler handler = connectedServerNodes.get(RpcConnectionInfo);
        if (handler != null) {
            handler.close();
        }
        connectedServerNodes.remove(RpcConnectionInfo);
        RpcConnectionInfoSet.remove(RpcConnectionInfo);
    }

    /**
     * 根据RpcConnectionInfo移除该节点和对应的RpcClientHandler
     * @param RpcConnectionInfo
     */

    public void removeHandler(RpcConnectionInfo RpcConnectionInfo) {
        RpcConnectionInfoSet.remove(RpcConnectionInfo);
        connectedServerNodes.remove(RpcConnectionInfo);
        log.info("Remove one connection, host: {}, port: {}", RpcConnectionInfo.getHost(), RpcConnectionInfo.getPort());
    }

    /**
     * 关闭所有连接
     */

    public void stop() {
        isRunning = false;
        for (RpcConnectionInfo RpcConnectionInfo : RpcConnectionInfoSet) {
            removeAndCloseHandler(RpcConnectionInfo);
        }
        signalAvailableHandler();
        threadPoolExecutor.shutdown();
        eventLoopGroup.shutdownGracefully();
    }
}


