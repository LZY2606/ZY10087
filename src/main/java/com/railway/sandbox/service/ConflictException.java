package com.railway.sandbox.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raised when two browsers edited the same old version. Carries enough
 * structured content for the UI to show the conflict and offer a
 * re-merge instead of silently overwriting.
 */
public class ConflictException extends RuntimeException {
    private final Map<String, Object> conflict = new LinkedHashMap<>();

    public ConflictException(String resource, String resourceId,
                             long clientVersion, long serverVersion,
                             Object clientPayload, Object serverPayload) {
        super("并发编辑冲突: " + resource + " " + resourceId);
        conflict.put("resource", resource);
        conflict.put("id", resourceId);
        conflict.put("clientBaseVersion", clientVersion);
        conflict.put("currentVersion", serverVersion);
        conflict.put("message", "您的编辑基于版本 " + clientVersion
                + "，服务器当前版本为 " + serverVersion + "，请对照冲突内容后重新合并提交");
        conflict.put("clientPayload", clientPayload);
        conflict.put("currentPayload", serverPayload);
    }

    public Map<String, Object> getConflict() { return conflict; }
}
