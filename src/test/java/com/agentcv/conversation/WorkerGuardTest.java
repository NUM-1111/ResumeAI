package com.agentcv.conversation;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class WorkerGuardTest {
    @TempDir Path folder;
    public static void main(String[] args) throws Exception {
        try(var guard=new WorkerGuard(Path.of(args[0]))){System.out.println("LOCKED");System.out.flush();System.in.read();}
    }
    Process child(Path lock) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),WorkerGuardTest.class.getName(),lock.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
    @Test void secondProcessCannotOwnLockAndCrashReleasesIt() throws Exception {
        Path lock=folder.resolve("worker.lock");Process first=child(lock);
        try{
            assertThat(first.inputReader().readLine()).isEqualTo("LOCKED");
            Process second=child(lock);
            try{assertThat(second.waitFor(3,TimeUnit.SECONDS)).isTrue();assertThat(second.exitValue()).isNotZero();}
            finally{second.destroyForcibly();}
            assertThatThrownBy(()->new WorkerGuard(lock)).isInstanceOf(java.io.IOException.class);
        }finally{first.destroyForcibly();assertThat(first.waitFor(3,TimeUnit.SECONDS)).isTrue();}
        try(var guard=new WorkerGuard(lock)){assertThat(Files.exists(lock)).isTrue();}
    }
}