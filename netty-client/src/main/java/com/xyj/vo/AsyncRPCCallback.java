package com.xyj.vo;

public interface AsyncRPCCallback {

    void success(Object result);

    void fail(Exception e);

}
