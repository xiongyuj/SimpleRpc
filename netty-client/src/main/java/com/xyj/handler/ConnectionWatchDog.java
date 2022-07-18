//package com.xyj.handler;
//
//import io.netty.bootstrap.Bootstrap;
//import io.netty.channel.*;
//import io.netty.util.Timeout;
//import io.netty.util.Timer;
//import io.netty.util.TimerTask;
//import lombok.Data;
//
//import java.net.SocketAddress;
//import java.util.concurrent.TimeUnit;
//
//
//public class ConnectionWatchDog extends ChannelHandlerAdapter implements TimerTask {
//
//    private Channel channel;
//
//    private SocketAddress remotePeer;
//
//    private final Timer timer;
//
//    private volatile boolean reconnect = true;
//
//    private int attempts;
//
//    private static int maxReconnectAttempts = 12;
//
//    /**
//     * 重连
//     * @param timeout
//     * @throws Exception
//     */
//    @Override
//    public void run(Timeout timeout) throws Exception {
//        ChannelFuture future = channel.connect(remotePeer);
//        future.addListener((ChannelFutureListener) f -> {
//            boolean succeed = f.isSuccess();
//            //如果重连失败，则调用ChannelInactive方法，再次出发重连事件，一直尝试maxReconnectAttempts次，如果失败则不再重连
//            if (!succeed) {
//                System.out.println("重连失败");
//                f.channel().pipeline().fireChannelInactive();
//            } else {
//                System.out.println("重连成功");
//            }
//        });
//    }
//
//    /**
//     * channel链路每次active的时候，将其连接的次数置0
//     * @param ctx
//     * @throws Exception
//     */
//
//    @Override
//    public void channelActive(ChannelHandlerContext ctx) throws Exception {
//        System.out.println("当前链路已经激活了，重连尝试次数重新置为0");
//        attempts = 0;
//        channel = ctx.channel();
//        remotePeer = channel.remoteAddress();
//        ctx.fireChannelActive();
//    }
//
//
//    /**
//     * channle链路inactive时进行重连，最多重连x次
//     * @param ctx
//     * @throws Exception
//     */
//    @Override
//    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
//        System.out.println("链接关闭");
//        if(reconnect){
//            if (attempts < maxReconnectAttempts) {
//                System.out.println("链接关闭，将进行重连");
//                attempts++;
//                timer.newTimeout(this, 2, TimeUnit.SECONDS);
//            }
//        }
//        ctx.fireChannelInactive();
//    }
//
//
//}
