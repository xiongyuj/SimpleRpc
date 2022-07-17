package com.xyj.connect;

public interface AsyncRPCCallback {

    void success(Object result);

    void fail(Exception e);

}
