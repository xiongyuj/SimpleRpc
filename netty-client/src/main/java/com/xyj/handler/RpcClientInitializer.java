package com.xyj.handler;

import com.xyj.codec.RpcDecoder;
import com.xyj.codec.RpcEncoder;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import com.xyj.message.Beat;
import com.xyj.message.RpcRequest;
import com.xyj.message.RpcResponse;

import com.xyj.serializer.Serializer;
import com.xyj.serializer.kryo.KryoSerializer;


import java.util.concurrent.TimeUnit;

public class RpcClientInitializer extends ChannelInitializer<SocketChannel> {

    @Override
    protected void initChannel(SocketChannel socketChannel) throws Exception {
        Serializer serializer = KryoSerializer.class.getDeclaredConstructor().newInstance();
        ChannelPipeline cp = socketChannel.pipeline();
        cp.addLast(new IdleStateHandler(0, 0, Beat.BEAT_INTERVAL, TimeUnit.SECONDS))//心跳连接
                .addLast(new RpcEncoder(RpcRequest.class, serializer))
                .addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 0))
                .addLast(new RpcDecoder(RpcResponse.class, serializer))
                .addLast(new RpcClientHandler());
                //.addLast(new ConnectionWatchDog());
    }
}
