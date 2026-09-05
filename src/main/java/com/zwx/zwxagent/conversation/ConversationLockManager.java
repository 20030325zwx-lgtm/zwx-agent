package com.zwx.zwxagent.conversation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ConversationLockManager {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public boolean tryLock(String conversationId) {
        ReentrantLock lock = locks.computeIfAbsent(conversationId, key -> new ReentrantLock());
        if (lock.isLocked()) return false;
        return lock.tryLock();
    }

    public void unlock(String conversationId) {
        ReentrantLock lock = locks.get(conversationId);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                locks.remove(conversationId, lock);
            }
        }
    }
}
