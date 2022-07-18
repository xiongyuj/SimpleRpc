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

    private CopyOnWriteArraySet<RpcConnectionInfo> rpcConnectionInfoSet = new CopyOnWriteArraySet<>();

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
     * 更新本地缓存节点，初次连接或重连时调用
     * @param serviceList
     */
    public void updateConnectedServer(List<RpcConnectionInfo> serviceList) {
        if (serviceList != null && serviceList.size() > 0) {
            HashSet<RpcConnectionInfo> serviceSet = new HashSet<>(serviceList.size());
            serviceSet.addAll(serviceList);

            //连接新增节点
            for (final RpcConnectionInfo rpcConnectionInfo : serviceSet) {
                if (!rpcConnectionInfoSet.contains(rpcConnectionInfo)) {
                    connectServerNode(rpcConnectionInfo);
                }
            }

            //移除并关闭连接断开的节点
            for (RpcConnectionInfo rpcConnectionInfo : rpcConnectionInfoSet) {
                if (!serviceSet.contains(rpcConnectionInfo)) {
                    log.info("Remove invalid service: " + rpcConnectionInfo.toJson());
                    removeAndCloseHandler(rpcConnectionInfo);
                }
            }
        } else {
            // 无可用服务，移除本地缓存
            log.error("No available service!");
            for (RpcConnectionInfo RpcConnectionInfo : rpcConnectionInfoSet) {
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
        if (type == PathChildrenCacheEvent.Type.CHILD_ADDED && !rpcConnectionInfoSet.contains(RpcConnectionInfo)) {
            connectServerNode(RpcConnectionInfo);
        } else if (type == PathChildrenCacheEvent.Type.CHILD_UPDATED) {
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
     * @param rpcConnectionInfo
     */

    private void connectServerNode(RpcConnectionInfo rpcConnectionInfo) {
        if (rpcConnectionInfo.getServiceInfoList() == null || rpcConnectionInfo.getServiceInfoList().isEmpty()) {
            log.info("No service on node, host: {}, port: {}", rpcConnectionInfo.getHost(), rpcConnectionInfo.getPort());
            return;
        }
        rpcConnectionInfoSet.add(rpcConnectionInfo);//添加到本地缓存
        log.info("New service node, host: {}, port: {}", rpcConnectionInfo.getHost(), rpcConnectionInfo.getPort());
        for (RpcServiceInfo rpcServiceInfo : rpcConnectionInfo.getServiceInfoList()) {
            log.info("New service info, name: {}, version: {}", rpcServiceInfo.getServiceName(), rpcServiceInfo.getVersion());
        }
        final InetSocketAddress remotePeer = new InetSocketAddress(rpcConnectionInfo.getHost(), rpcConnectionInfo.getPort());
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
                            connectedServerNodes.put(rpcConnectionInfo, handler);
                            handler.setRpcConnectionInfo(rpcConnectionInfo);
                            //连接成功后，connection分配handler
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
        //本地节点缓存为空时需要等待handler
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
     * @param rpcConnectionInfo
     */

    private void removeAndCloseHandler(RpcConnectionInfo rpcConnectionInfo) {
        RpcClientHandler handler = connectedServerNodes.get(rpcConnectionInfo);
        if (handler != null) {
            handler.close();
        }
        connectedServerNodes.remove(rpcConnectionInfo);
        rpcConnectionInfoSet.remove(rpcConnectionInfo);
    }

    /**
     * 根据RpcConnectionInfo移除该节点和对应的RpcClientHandler
     * @param rpcConnectionInfo
     */

    public void removeHandler(RpcConnectionInfo rpcConnectionInfo) {
        rpcConnectionInfoSet.remove(rpcConnectionInfo);
        connectedServerNodes.remove(rpcConnectionInfo);
        log.info("Remove one connection, host: {}, port: {}", rpcConnectionInfo.getHost(), rpcConnectionInfo.getPort());
    }

    /**
     * 关闭所有连接
     */
    public void stop() {
        isRunning = false;
        for (RpcConnectionInfo RpcConnectionInfo : rpcConnectionInfoSet) {
            removeAndCloseHandler(RpcConnectionInfo);
        }
        signalAvailableHandler();
        threadPoolExecutor.shutdown();
        eventLoopGroup.shutdownGracefully();
    }
}


