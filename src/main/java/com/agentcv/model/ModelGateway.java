package com.agentcv.model;

import java.util.Map;

public interface ModelGateway {
    record Result(String answer,String supplierId,Map<String,Object> usage) {}
    class Failure extends RuntimeException {
        public final String code; public final boolean unknown;
        public Failure(String code,boolean unknown){super(code);this.code=code;this.unknown=unknown;}
    }
    boolean configured();
    String model();
    Result complete(String inputJson);
}