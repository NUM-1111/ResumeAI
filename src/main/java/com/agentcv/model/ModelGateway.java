package com.agentcv.model;

import java.util.Map;

public interface ModelGateway {
    record Result(String answer,String supplierId,Map<String,Object> usage,Map<String,Object> diagnostics) {
        public Result(String answer,String supplierId,Map<String,Object> usage){this(answer,supplierId,usage,Map.of());}
    }
    class Failure extends RuntimeException {
        public final String code; public final boolean unknown;
        public final String supplierId;public final Map<String,Object> usage;
        public Map<String,Object> diagnostics=Map.of();
        public Failure(String code,boolean unknown){this(code,unknown,"",Map.of());}
        public Failure(String code,boolean unknown,String supplierId,Map<String,Object> usage){super(code);this.code=code;this.unknown=unknown;this.supplierId=supplierId;this.usage=usage;}
    }
    boolean configured();
    String model();
    Result complete(String inputJson);
}