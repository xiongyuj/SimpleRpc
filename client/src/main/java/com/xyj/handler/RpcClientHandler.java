package com.xyj.handler;

import com.xyj.connect.ConnectionManager;
import com.xyj.vo.RpcConnectionInfo;
import com.xyj.vo.RpcFuture;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.timeout.IdleStateEvent;
import com.xyj.message.Beat;
import com.xyj.message.RpcRequest;
import com.xyj.message.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;


/**
 * RPC请求的Handler，一个Channel对应着一个RPCHandler实例，因为该Handler不是Shareable的
 */
@Slf4j
public class RpcClientHandler extends SimpleChannelInboundHandler<RpcResponse> {

    private ConcurrentHashMap<String, RpcFuture> pendingRPC =
            new ConcurrentHashMap<>();//记录所有待处理的rpc请求

    private volatile Channel channel;

    private SocketAddress remotePeer;

    private RpcConnectionInfo rpcConnectionInfo;

    /**
     * Channel初始化时该handler处理的socket
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.remotePeer = this.channel.remoteAddress();
    }

    /**
     * 该channel关闭时移除对应的handler
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        ConnectionManager.getInstance().removeHandler(rpcConnectionInfo);
    }

    /**
     * Channel注册时初始化Handler对应的channel
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
        this.channel = ctx.channel();
    }

    /**
     * 根据server返回的response来更新对应的rpcFuture
     * @param channelHandlerContext
     * @param rpcResponse
     * @throws Exception
     */
    @Override
    protected void messageReceived(ChannelHandlerContext channelHandlerContext, RpcResponse rpcResponse) throws Exception {
        String requestId = rpcResponse.getRequestId();
        log.debug("Receive response: " + requestId);
        RpcFuture rpcFuture = pendingRPC.get(requestId);
        //该RPC请求结束，从map中移除该连接，并发起该RPCFuture所有回调
        if (rpcFuture != null) {
            pendingRPC.remove(requestId);
            rpcFuture.done(rpcResponse);
        } else {
            log.warn("Can not get pending response for request id: " + requestId);
        }
    }

    /**
     * 异常处理
     * @param ctx
     * @param cause
     * @throws Exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Client caught exception: " + cause.getMessage());
        ctx.close();
    }

    /**
     * 心跳检测
     * idle时长超过设定，则会触发userEventTriggered方法
     * @param ctx
     * @param evt
     * @throws Exception
     */

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            sendRequest(Beat.BEAT_PING);
            log.debug("Client send beat-ping to " + remotePeer);
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }



    //utils


    /**
     * 关闭连接
     */
    public void close() {
        channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * 发送request
     * @param request
     * @return
     */
    public RpcFuture sendRequest(RpcRequest request) {
        RpcFuture rpcFuture = new RpcFuture(request);
        pendingRPC.put(request.getRequestId(), rpcFuture);
        try {
            ChannelFuture channelFuture = channel.writeAndFlush(request).sync();
            if (!channelFuture.isSuccess()) {
                log.error("Send request {} error", request.getRequestId());
            }
        } catch (InterruptedException e) {
            log.error("Send request exception: " + e.getMessage());
        }

        return rpcFuture;
    }


    public void setRpcConnectionInfo(RpcConnectionInfo RpcConnectionInfo) {
        this.rpcConnectionInfo = RpcConnectionInfo;
    }


}
