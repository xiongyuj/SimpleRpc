package com.xyj.message;

public final class Beat {

    public static final int BEAT_INTERVAL = 5;  //客户端心跳检测间隔
    public static final int BEAT_TIMEOUT = 3 * BEAT_INTERVAL; //服务端检测连接的超时限定
    public static final String BEAT_ID = "BEAT_PING_PONG";

    public static RpcRequest BEAT_PING;

    static {
        BEAT_PING = new RpcRequest() {};
        BEAT_PING.setRequestId(BEAT_ID);//所有心跳连接的requestId相同
    }

}
