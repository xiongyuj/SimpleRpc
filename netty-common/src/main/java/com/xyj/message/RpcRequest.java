package com.xyj.message;


import lombok.Data;

import java.io.Serializable;

/**
 * RPC Request
 */
@Data
public class RpcRequest implements Serializable {

    private static final long serialVersionUID = -2524587347775862771L;

    private String requestId;

    private String className;

    private String methodName;

    private Class<?>[] parameterTypes;

    private Object[] parameters;

    private String version;

}