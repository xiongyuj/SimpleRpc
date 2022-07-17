package com.xyj.handler;


import com.xyj.message.Beat;
import com.xyj.message.RpcRequest;
import com.xyj.message.RpcResponse;
import com.xyj.util.ServiceUtil;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.reflect.FastClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * RPC Handler（RPC request processor）
 * 处理请求连接
 */
@Slf4j
public class RpcServerHandler extends SimpleChannelInboundHandler<RpcRequest> {

    private final Map<String, Object> handlerMap;
    private final ThreadPoolExecutor serverHandlerPool;

    public RpcServerHandler(Map<String, Object> handlerMap, final ThreadPoolExecutor threadPoolExecutor) {
        this.handlerMap = handlerMap;
        this.serverHandlerPool = threadPoolExecutor;
    }


    /**
     * 根据RPC请求执行方法得到结果
     * @param request
     * @return
     * @throws Throwable
     */
    private Object handle(RpcRequest request) throws Throwable {
        String className = request.getClassName();
        String version = request.getVersion();
        String serviceKey = ServiceUtil.makeServiceKey(className, version);
        Object serviceBean = handlerMap.get(serviceKey);
        if (serviceBean == null) {
            log.error("Can not find service implement with interface name: {} and version: {}", className, version);
            return null;
        }

        Class<?> serviceClass = serviceBean.getClass();
        String methodName = request.getMethodName();
        Class<?>[] parameterTypes = request.getParameterTypes();
        Object[] parameters = request.getParameters();

        log.debug(serviceClass.getName());
        log.debug(methodName);
        for (int i = 0; i < parameterTypes.length; ++i) {
            log.debug(parameterTypes[i].getName());
        }
        for (int i = 0; i < parameters.length; ++i) {
            log.debug(parameters[i].toString());
        }

        // JDK reflect
//        Method method = serviceClass.getMethod(methodName, parameterTypes);
//        method.setAccessible(true);
//        return method.invoke(serviceBean, parameters);

        // Cglib reflect
        FastClass serviceFastClass = FastClass.create(serviceClass);
//        FastMethod serviceFastMethod = serviceFastClass.getMethod(methodName, parameterTypes);
//        return serviceFastMethod.invoke(serviceBean, parameters);
        // for higher-performance
        int methodIndex = serviceFastClass.getIndex(methodName, parameterTypes);
        return serviceFastClass.invoke(methodIndex, serviceBean, parameters);
    }

    /**
     * 处理RPC请求，根据不同请求类型进行处理
     * @param channelHandlerContext
     * @param rpcRequest
     * @throws Exception
     */

    @Override
    protected void messageReceived(ChannelHandlerContext channelHandlerContext, RpcRequest rpcRequest) throws Exception {
        // 检测是否为心跳
        if (Beat.BEAT_ID.equalsIgnoreCase(rpcRequest.getRequestId())) {
            log.info("Server read heartbeat ping");
            return;
        }

        serverHandlerPool.execute(new Runnable() {
            @Override
            public void run() {
                log.info("Receive request " + rpcRequest.getRequestId());
                RpcResponse response = new RpcResponse();
                response.setRequestId(rpcRequest.getRequestId());
                try {
                    Object result = handle(rpcRequest);
                    response.setResult(result);
                } catch (Throwable t) {
                    response.setError(t.toString());
                    log.error("RPC Server handle request error", t);
                }
                channelHandlerContext.writeAndFlush(response).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture channelFuture) throws Exception {
                        log.info("Send response for request " + rpcRequest.getRequestId());
                    }
                });
            }
        });
    }


    /**
     * 异常处理
     * @param ctx
     * @param cause
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Server caught exception: " + cause.getMessage());
        ctx.close();
    }

    /**
     * 心跳检测，channel超过30s空闲，关闭该连接
     * @param ctx
     * @param evt
     * @throws Exception
     */

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.channel().close();
            log.warn("Channel idle in last {} seconds, close it", Beat.BEAT_TIMEOUT);
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
}
