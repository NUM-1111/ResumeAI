package com.agentcv.conversation;

import java.nio.channels.*;
import java.nio.file.*;
import java.io.IOException;

/** Acquired before startup recovery; held until all local execution has stopped. */
public final class WorkerGuard implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    public WorkerGuard(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock acquired=null;
        try{acquired=channel.tryLock();if(acquired==null)throw new IOException("AI worker already running");}
        catch(IOException|OverlappingFileLockException error){channel.close();throw new IOException("AI worker already running or lock unavailable");}
        lock=acquired;
    }
    @Override public void close() throws IOException {try{lock.release();}finally{channel.close();}}
}